package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.friend.service.FriendCommands;
import com.teamehera.kiikick.match.repository.MatchCommentRepository;
import com.teamehera.kiikick.match.repository.MatchCommentRepository.Comment;
import com.teamehera.kiikick.match.service.*;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@MatchApiController
@RequestMapping("/api/v1/matches/{matchId}/comments")
public class MatchCommentController {
    private final MatchSocialAccess access;
    private final MatchCommentRepository comments;
    private final FriendCommands commands;
    private final ObjectMapper json;
    private final Duration retention;

    public MatchCommentController(MatchSocialAccess access, MatchCommentRepository comments, FriendCommands commands,
                                  ObjectMapper json,
                                  @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.access = access;
        this.comments = comments;
        this.commands = commands;
        this.json = json;
        this.retention = retention;
    }

    @Operation(summary = "본인 또는 친구 경기 댓글 목록 조회")
    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Principal principal, @PathVariable UUID matchId,
                     @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        UUID actor = MatchController.actor(principal);
        access.requireVisibleMatch(actor, matchId);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw MatchListQuery.InvalidListRequest.of("INVALID_REQUEST");
        }
        String scope = "1|comments|" + actor + "|" + matchId + "|";
        var boundary = cursor == null ? null : decode(cursor, scope);
        var rows = comments.page(matchId, actor, boundary == null ? null : boundary.time(),
                boundary == null ? null : boundary.id(), size + 1);
        boolean hasNext = rows.size() > size;
        var page = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (hasNext) {
            var last = page.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (scope + last.createdAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        var meta = MatchApiResponse.Meta.create();
        return new Page(page.stream().map(CommentResponse::from).toList(),
                new MatchListQuery.PageMeta(meta.requestId(), meta.serverTime(), new MatchListQuery.Pagination(next, hasNext)));
    }

    @Operation(summary = "본인 또는 친구 경기에 댓글 작성")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(Principal principal, @PathVariable UUID matchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = MatchController.actor(principal);
        var match = access.requireVisibleMatch(actor, matchId);
        String path = path(matchId);
        var request = new MatchMutation.Request(actor, matchId, "POST", path, MatchController.key(key), ifMatch, body);
        // 친구 삭제·차단과 같은 사용자 잠금 순서를 사용하고 재전송에도 접근 권한을 다시 검사한다.
        return replay(commands.forUser(request, expiresAt(), match.ownerId(),
                () -> access.requireVisibleMatch(actor, matchId), () -> {
                    var input = MatchSettings.readBody(json, body);
                    MatchSettings.checkObject(input, "body", Set.of("content"));
                    var content = input.path("content");
                    if (!content.isString() || content.stringValue().isBlank()
                            || content.stringValue().codePointCount(0, content.stringValue().length()) > 1000) {
                        throw new MatchSettings.InvalidSettings(List.of("content"));
                    }
                    var comment = new Comment(UUID.randomUUID(), matchId, actor, content.stringValue().strip(),
                            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
                    comments.save(comment);
                    var meta = MatchApiResponse.Meta.create();
                    return new MatchMutation.Response(201,
                            json.writeValueAsString(Map.of(MatchApiResponse.REQUEST_ID_HEADER, meta.requestId().toString(),
                                    HttpHeaders.LOCATION, path + "/" + comment.id())),
                            json.writeValueAsString(new MatchApiResponse.Success<>(CommentResponse.from(comment), meta)));
                }));
    }

    @Operation(summary = "내가 작성한 경기 댓글 삭제")
    @DeleteMapping("/{commentId}")
    public ResponseEntity<String> delete(Principal principal, @PathVariable UUID matchId, @PathVariable UUID commentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = MatchController.actor(principal);
        if (body != null) {
            throw new MatchController.InvalidRequest();
        }
        var match = access.requireVisibleMatch(actor, matchId);
        var request = new MatchMutation.Request(actor, matchId, "DELETE", path(matchId) + "/" + commentId,
                MatchController.key(key), ifMatch, null);
        return replay(commands.forUser(request, expiresAt(), match.ownerId(),
                () -> access.requireVisibleMatch(actor, matchId), () -> {
                    if (comments.delete(matchId, commentId, actor) != 1) {
                        throw new MatchOwnership.ResourceNotFound();
                    }
                    return new MatchMutation.Response(204, json.writeValueAsString(Map.of(
                            MatchApiResponse.REQUEST_ID_HEADER, UUID.randomUUID().toString())), null);
                }));
    }

    private static Boundary decode(String cursor, String scope) {
        if (cursor.length() <= 320) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (decoded.startsWith(scope)) {
                    String[] parts = decoded.substring(scope.length()).split("\\|", -1);
                    if (parts.length == 2) {
                        var time = LocalDateTime.parse(parts[0]);
                        var id = UUID.fromString(parts[1]);
                        if (time.getYear() >= 1000 && time.getYear() <= 9999 && id.toString().equals(parts[1])) {
                            return new Boundary(time, id);
                        }
                    }
                }
            } catch (IllegalArgumentException | DateTimeParseException ignored) {
                // 다른 사용자·경기의 커서와 잘못된 커서는 동일하게 거절한다.
            }
        }
        throw MatchListQuery.InvalidListRequest.of("INVALID_CURSOR");
    }

    private ResponseEntity<String> replay(MatchMutation.Response response) {
        var result = ResponseEntity.status(response.status());
        if (response.bodyJson() != null) {
            result.contentType(MediaType.APPLICATION_JSON);
        }
        json.readTree(response.headersJson()).properties()
                .forEach(entry -> result.header(entry.getKey(), entry.getValue().asText()));
        return result.body(response.bodyJson());
    }

    private static String path(UUID matchId) { return "/api/v1/matches/" + matchId + "/comments"; }
    private LocalDateTime expiresAt() { return LocalDateTime.now(ZoneOffset.UTC).plus(retention); }
    private record Boundary(LocalDateTime time, UUID id) {}
    public record Page(List<CommentResponse> data, MatchListQuery.PageMeta meta) {}
    public record CommentResponse(UUID id, UUID matchId, UUID authorId, String content, OffsetDateTime createdAt) {
        static CommentResponse from(Comment comment) {
            return new CommentResponse(comment.id(), comment.matchId(), comment.authorId(), comment.content(),
                    MatchApiResponse.fromUtc(comment.createdAt()));
        }
    }
}
