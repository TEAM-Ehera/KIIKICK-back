package com.teamehera.kiikick.pitch.service;

import com.teamehera.kiikick.common.exception.ApiException;
import com.teamehera.kiikick.pitch.domain.Pitch;
import com.teamehera.kiikick.pitch.domain.PitchCorner;
import com.teamehera.kiikick.pitch.dto.PitchCornerCreateRequest;
import com.teamehera.kiikick.pitch.dto.PitchCornerResponse;
import com.teamehera.kiikick.pitch.dto.PitchCornerUpdateRequest;
import com.teamehera.kiikick.pitch.dto.PitchCreateRequest;
import com.teamehera.kiikick.pitch.dto.PitchCreationResult;
import com.teamehera.kiikick.pitch.dto.PitchResponse;
import com.teamehera.kiikick.pitch.dto.PitchSummaryResponse;
import com.teamehera.kiikick.pitch.dto.PitchUpdateRequest;
import com.teamehera.kiikick.pitch.repository.PitchCornerRepository;
import com.teamehera.kiikick.pitch.repository.PitchRepository;
import com.teamehera.kiikick.user.domain.User;
import com.teamehera.kiikick.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PitchService {

    private static final Set<Byte> REQUIRED_CORNER_ORDERS = Set.of(
            (byte) 1,
            (byte) 2,
            (byte) 3,
            (byte) 4
    );

    private final UserRepository userRepository;
    private final PitchRepository pitchRepository;
    private final PitchCornerRepository pitchCornerRepository;

    public PitchService(
            UserRepository userRepository,
            PitchRepository pitchRepository,
            PitchCornerRepository pitchCornerRepository
    ) {
        this.userRepository = userRepository;
        this.pitchRepository = pitchRepository;
        this.pitchCornerRepository = pitchCornerRepository;
    }

    @Transactional
    public PitchCreationResult create(UUID userId, PitchCreateRequest request) {
        validateCreateCorners(request.corners());
        User user = findUser(userId);

        return pitchRepository.findByClientPitchId(request.clientPitchId())
                .map(existing -> handleDuplicateRegistration(existing, userId, request))
                .orElseGet(() -> createNewPitch(user, request));
    }

    public List<PitchSummaryResponse> findAll(UUID userId) {
        ensureUserExists(userId);
        return pitchRepository.findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    public PitchResponse findOne(UUID userId, UUID pitchId) {
        Pitch pitch = findActiveOwnedPitch(userId, pitchId);
        return toResponse(pitch, findSortedCorners(pitch.getId()));
    }

    @Transactional
    public PitchResponse update(UUID userId, UUID pitchId, PitchUpdateRequest request) {
        validateUpdateCorners(request.corners());
        Pitch pitch = findActiveOwnedPitch(userId, pitchId);
        List<PitchCorner> corners = findSortedCorners(pitch.getId());
        Map<Byte, PitchCorner> cornersByOrder = corners.stream()
                .collect(Collectors.toMap(PitchCorner::getCornerOrder, Function.identity()));

        if (!cornersByOrder.keySet().equals(REQUIRED_CORNER_ORDERS)) {
            throw ApiException.conflict("저장된 운동장의 모서리 구성이 올바르지 않습니다.");
        }

        pitch.changeName(request.name());
        for (PitchCornerUpdateRequest cornerRequest : request.corners()) {
            PitchCorner corner = cornersByOrder.get(cornerRequest.cornerOrder());
            corner.changeCoordinates(cornerRequest.latitude(), cornerRequest.longitude());
        }

        pitchRepository.flush();
        pitchCornerRepository.flush();
        return toResponse(pitch, corners);
    }

    @Transactional
    public void delete(UUID userId, UUID pitchId) {
        Pitch pitch = findActiveOwnedPitch(userId, pitchId);
        pitch.delete();
        pitchRepository.flush();
    }

    private PitchCreationResult createNewPitch(User user, PitchCreateRequest request) {
        Pitch pitch = new Pitch(user, request.clientPitchId(), request.name());
        List<PitchCorner> corners = request.corners().stream()
                .map(corner -> toEntity(pitch, corner))
                .sorted(Comparator.comparing(PitchCorner::getCornerOrder))
                .toList();

        try {
            pitchRepository.saveAndFlush(pitch);
            pitchCornerRepository.saveAllAndFlush(corners);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("clientPitchId 또는 모서리 정보가 기존 데이터와 충돌합니다.");
        }

        return new PitchCreationResult(toResponse(pitch, corners), true);
    }

    private PitchCreationResult handleDuplicateRegistration(
            Pitch existing,
            UUID userId,
            PitchCreateRequest request
    ) {
        if (!existing.getUser().getId().equals(userId) || existing.getDeletedAt() != null) {
            throw ApiException.conflict("이미 사용 중인 clientPitchId입니다.");
        }

        List<PitchCorner> existingCorners = findSortedCorners(existing.getId());
        if (!hasSameRegistrationContent(existing, existingCorners, request)) {
            throw ApiException.conflict("같은 clientPitchId에 서로 다른 등록 내용이 전달되었습니다.");
        }

        return new PitchCreationResult(toResponse(existing, existingCorners), false);
    }

    private boolean hasSameRegistrationContent(
            Pitch existing,
            List<PitchCorner> existingCorners,
            PitchCreateRequest request
    ) {
        if (!existing.getName().equals(request.name()) || existingCorners.size() != request.corners().size()) {
            return false;
        }

        Map<Byte, PitchCornerCreateRequest> requestedByOrder = request.corners().stream()
                .collect(Collectors.toMap(PitchCornerCreateRequest::cornerOrder, Function.identity()));

        return existingCorners.stream().allMatch(existingCorner -> {
            PitchCornerCreateRequest requested = requestedByOrder.get(existingCorner.getCornerOrder());
            return requested != null
                    && existingCorner.getCornerRole().equals(requested.cornerRole())
                    && sameNumber(existingCorner.getLatitude(), requested.latitude())
                    && sameNumber(existingCorner.getLongitude(), requested.longitude());
        });
    }

    private boolean sameNumber(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) == 0;
    }

    private PitchCorner toEntity(Pitch pitch, PitchCornerCreateRequest request) {
        return new PitchCorner(
                pitch,
                request.cornerOrder(),
                request.cornerRole(),
                request.latitude(),
                request.longitude()
        );
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("테스트 사용자를 찾을 수 없습니다."));
    }

    private void ensureUserExists(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw ApiException.notFound("테스트 사용자를 찾을 수 없습니다.");
        }
    }

    private Pitch findActiveOwnedPitch(UUID userId, UUID pitchId) {
        return pitchRepository.findByIdAndUserIdAndDeletedAtIsNull(pitchId, userId)
                .orElseThrow(() -> ApiException.notFound("운동장을 찾을 수 없습니다."));
    }

    private List<PitchCorner> findSortedCorners(UUID pitchId) {
        return pitchCornerRepository.findAllByPitchIdOrderByCornerOrderAsc(pitchId);
    }

    private void validateCreateCorners(List<PitchCornerCreateRequest> corners) {
        Set<Byte> orders = corners.stream()
                .map(PitchCornerCreateRequest::cornerOrder)
                .collect(Collectors.toSet());
        if (!orders.equals(REQUIRED_CORNER_ORDERS)) {
            throw ApiException.badRequest("cornerOrder는 1, 2, 3, 4를 정확히 한 번씩 포함해야 합니다.");
        }

        long distinctRoles = corners.stream()
                .map(PitchCornerCreateRequest::cornerRole)
                .distinct()
                .count();
        if (distinctRoles != corners.size()) {
            throw ApiException.badRequest("cornerRole은 중복될 수 없습니다.");
        }
    }

    private void validateUpdateCorners(List<PitchCornerUpdateRequest> corners) {
        Set<Byte> orders = corners.stream()
                .map(PitchCornerUpdateRequest::cornerOrder)
                .collect(Collectors.toSet());
        if (!orders.equals(REQUIRED_CORNER_ORDERS)) {
            throw ApiException.badRequest("cornerOrder는 1, 2, 3, 4를 정확히 한 번씩 포함해야 합니다.");
        }
    }

    private PitchResponse toResponse(Pitch pitch, List<PitchCorner> corners) {
        List<PitchCornerResponse> cornerResponses = corners.stream()
                .sorted(Comparator.comparing(PitchCorner::getCornerOrder))
                .map(corner -> new PitchCornerResponse(
                        corner.getId(),
                        corner.getCornerOrder(),
                        corner.getCornerRole(),
                        corner.getLatitude(),
                        corner.getLongitude()
                ))
                .toList();

        return new PitchResponse(
                pitch.getId(),
                pitch.getUser().getId(),
                pitch.getClientPitchId(),
                pitch.getName(),
                cornerResponses,
                pitch.getCreatedAt(),
                pitch.getUpdatedAt()
        );
    }

    private PitchSummaryResponse toSummaryResponse(Pitch pitch) {
        return new PitchSummaryResponse(
                pitch.getId(),
                pitch.getUser().getId(),
                pitch.getClientPitchId(),
                pitch.getName(),
                pitch.getCreatedAt(),
                pitch.getUpdatedAt()
        );
    }
}
