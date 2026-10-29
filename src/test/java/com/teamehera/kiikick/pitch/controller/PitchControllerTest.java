package com.teamehera.kiikick.pitch.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.teamehera.kiikick.pitch.domain.Pitch;
import com.teamehera.kiikick.pitch.domain.PitchCorner;
import com.teamehera.kiikick.pitch.repository.PitchCornerRepository;
import com.teamehera.kiikick.pitch.repository.PitchRepository;
import com.teamehera.kiikick.user.domain.User;
import com.teamehera.kiikick.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PitchControllerTest {

    private static final String USER_HEADER = "X-Test-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PitchRepository pitchRepository;

    @Autowired
    private PitchCornerRepository pitchCornerRepository;

    @Test
    void createsPitchWithFourCornersAndFindsDetailInCornerOrder() throws Exception {
        User user = userRepository.saveAndFlush(new User("api-user"));
        UUID clientPitchId = UUID.randomUUID();

        String location = mockMvc.perform(post("/api/pitches")
                        .header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(clientPitchId, "api-pitch")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.clientPitchId").value(clientPitchId.toString()))
                .andExpect(jsonPath("$.name").value("api-pitch"))
                .andExpect(jsonPath("$.corners.length()").value(4))
                .andExpect(jsonPath("$.corners[0].cornerOrder").value(1))
                .andExpect(jsonPath("$.corners[3].cornerOrder").value(4))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(get(location).header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("api-pitch"))
                .andExpect(jsonPath("$.corners[0].cornerOrder").value(1))
                .andExpect(jsonPath("$.corners[1].cornerOrder").value(2))
                .andExpect(jsonPath("$.corners[2].cornerOrder").value(3))
                .andExpect(jsonPath("$.corners[3].cornerOrder").value(4));
    }

    @Test
    void returnsExistingPitchForIdenticalClientPitchRetry() throws Exception {
        User user = userRepository.saveAndFlush(new User("api-user"));
        UUID clientPitchId = UUID.randomUUID();
        String request = createRequest(clientPitchId, "api-pitch");

        mockMvc.perform(post("/api/pitches")
                        .header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/pitches")
                        .header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sameRegistrationInDifferentOrder(clientPitchId, "api-pitch")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientPitchId").value(clientPitchId.toString()));

        assertEquals(1, pitchRepository.count());
        assertEquals(4, pitchCornerRepository.count());
    }

    @Test
    void rejectsDifferentContentForSameClientPitchId() throws Exception {
        User user = userRepository.saveAndFlush(new User("api-user"));
        UUID clientPitchId = UUID.randomUUID();

        mockMvc.perform(post("/api/pitches")
                        .header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(clientPitchId, "first-name")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/pitches")
                        .header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(clientPitchId, "different-name")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void listsOnlyActiveOwnedPitchesInNewestFirstOrder() throws Exception {
        User owner = userRepository.save(new User("owner"));
        User otherOwner = userRepository.save(new User("other-owner"));
        Pitch olderPitch = savePitch(owner, "older");
        Pitch newerPitch = savePitch(owner, "newer");
        Pitch deletedPitch = savePitch(owner, "deleted");
        savePitch(otherOwner, "other");
        deletedPitch.delete();
        pitchRepository.flush();

        mockMvc.perform(get("/api/pitches").header(USER_HEADER, owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newerPitch.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(olderPitch.getId().toString()));
    }

    @Test
    void hidesPitchFromOtherOwnerForReadUpdateAndDelete() throws Exception {
        User owner = userRepository.save(new User("owner"));
        User otherUser = userRepository.save(new User("other"));
        Pitch pitch = savePitch(owner, "owned-pitch");

        mockMvc.perform(get("/api/pitches/{pitchId}", pitch.getId())
                        .header(USER_HEADER, otherUser.getId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/pitches/{pitchId}", pitch.getId())
                        .header(USER_HEADER, otherUser.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest("changed-name")))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/pitches/{pitchId}", pitch.getId())
                        .header(USER_HEADER, otherUser.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesNameAndCoordinatesButKeepsCornerIdentity() throws Exception {
        User owner = userRepository.save(new User("owner"));
        Pitch pitch = savePitchWithCorners(owner, "before-update");
        LocalDateTime updatedAtBefore = pitch.getUpdatedAt();
        List<UUID> cornerIdsBefore = pitchCornerRepository
                .findAllByPitchIdOrderByCornerOrderAsc(pitch.getId()).stream()
                .map(PitchCorner::getId)
                .toList();

        mockMvc.perform(put("/api/pitches/{pitchId}", pitch.getId())
                        .header(USER_HEADER, owner.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest("after-update")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("after-update"))
                .andExpect(jsonPath("$.corners[0].cornerOrder").value(1))
                .andExpect(jsonPath("$.corners[0].cornerRole").value("TOP_LEFT"))
                .andExpect(jsonPath("$.corners[0].latitude").value(38.1001));

        List<PitchCorner> cornersAfter = pitchCornerRepository
                .findAllByPitchIdOrderByCornerOrderAsc(pitch.getId());
        Pitch pitchAfter = pitchRepository.findById(pitch.getId()).orElseThrow();
        assertEquals(cornerIdsBefore, cornersAfter.stream().map(PitchCorner::getId).toList());
        assertEquals(0, cornersAfter.getFirst().getLatitude().compareTo(new BigDecimal("38.1001")));
        assertTrue(pitchAfter.getUpdatedAt().isAfter(updatedAtBefore));
    }

    @Test
    void softDeletesPitchAndExcludesItFromReadAndSecondDelete() throws Exception {
        User owner = userRepository.save(new User("owner"));
        Pitch pitch = savePitch(owner, "delete-target");

        mockMvc.perform(delete("/api/pitches/{pitchId}", pitch.getId())
                        .header(USER_HEADER, owner.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/pitches/{pitchId}", pitch.getId())
                        .header(USER_HEADER, owner.getId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/pitches/{pitchId}", pitch.getId())
                        .header(USER_HEADER, owner.getId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/pitches/{pitchId}", pitch.getId())
                        .header(USER_HEADER, owner.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest("cannot-update")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/pitches").header(USER_HEADER, owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        Pitch storedPitch = pitchRepository.findById(pitch.getId()).orElseThrow();
        assertNotNull(storedPitch.getDeletedAt());
    }

    @Test
    void rejectsInvalidCornerOrder() throws Exception {
        User owner = userRepository.saveAndFlush(new User("owner"));

        mockMvc.perform(post("/api/pitches")
                        .header(USER_HEADER, owner.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidCornerRequest(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void rejectsDuplicateCornerRole() throws Exception {
        User owner = userRepository.saveAndFlush(new User("owner"));

        mockMvc.perform(post("/api/pitches")
                        .header(USER_HEADER, owner.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateRoleRequest(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("cornerRole은 중복될 수 없습니다."));
    }

    @Test
    void rejectsCoordinateOutsideGpsRange() throws Exception {
        User owner = userRepository.saveAndFlush(new User("owner"));
        String request = createRequest(UUID.randomUUID(), "invalid-coordinate")
                .replace("37.1001", "91.0");

        mockMvc.perform(post("/api/pitches")
                        .header(USER_HEADER, owner.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void returnsBadRequestForMissingOrMalformedUserHeader() throws Exception {
        mockMvc.perform(get("/api/pitches"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/pitches").header(USER_HEADER, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void returnsNotFoundForUnknownTestUser() throws Exception {
        mockMvc.perform(get("/api/pitches").header(USER_HEADER, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void exposesPitchApiThroughOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("KIIKICK API"))
                .andExpect(jsonPath("$.paths['/api/pitches']").exists())
                .andExpect(jsonPath("$.paths['/api/pitches/{pitchId}']").exists());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    private Pitch savePitch(User owner, String name) {
        return pitchRepository.saveAndFlush(new Pitch(owner, UUID.randomUUID(), name));
    }

    private Pitch savePitchWithCorners(User owner, String name) {
        Pitch pitch = savePitch(owner, name);
        pitchCornerRepository.saveAllAndFlush(List.of(
                corner(pitch, 1, "TOP_LEFT", "37.1001", "127.1001"),
                corner(pitch, 2, "TOP_RIGHT", "37.1002", "127.1002"),
                corner(pitch, 3, "BOTTOM_RIGHT", "37.1003", "127.1003"),
                corner(pitch, 4, "BOTTOM_LEFT", "37.1004", "127.1004")
        ));
        return pitch;
    }

    private PitchCorner corner(Pitch pitch, int order, String role, String latitude, String longitude) {
        return new PitchCorner(
                pitch,
                (byte) order,
                role,
                new BigDecimal(latitude),
                new BigDecimal(longitude)
        );
    }

    private String createRequest(UUID clientPitchId, String name) {
        return """
                {
                  "clientPitchId": "%s",
                  "name": "%s",
                  "corners": [
                    {"cornerOrder": 4, "cornerRole": "BOTTOM_LEFT", "latitude": 37.1004, "longitude": 127.1004},
                    {"cornerOrder": 2, "cornerRole": "TOP_RIGHT", "latitude": 37.1002, "longitude": 127.1002},
                    {"cornerOrder": 1, "cornerRole": "TOP_LEFT", "latitude": 37.1001, "longitude": 127.1001},
                    {"cornerOrder": 3, "cornerRole": "BOTTOM_RIGHT", "latitude": 37.1003, "longitude": 127.1003}
                  ]
                }
                """.formatted(clientPitchId, name);
    }

    private String updateRequest(String name) {
        return """
                {
                  "name": "%s",
                  "corners": [
                    {"cornerOrder": 3, "latitude": 38.1003, "longitude": 128.1003},
                    {"cornerOrder": 1, "latitude": 38.1001, "longitude": 128.1001},
                    {"cornerOrder": 4, "latitude": 38.1004, "longitude": 128.1004},
                    {"cornerOrder": 2, "latitude": 38.1002, "longitude": 128.1002}
                  ]
                }
                """.formatted(name);
    }

    private String sameRegistrationInDifferentOrder(UUID clientPitchId, String name) {
        return """
                {
                  "clientPitchId": "%s",
                  "name": "%s",
                  "corners": [
                    {"cornerOrder": 1, "cornerRole": "TOP_LEFT", "latitude": 37.1001, "longitude": 127.1001},
                    {"cornerOrder": 2, "cornerRole": "TOP_RIGHT", "latitude": 37.1002, "longitude": 127.1002},
                    {"cornerOrder": 3, "cornerRole": "BOTTOM_RIGHT", "latitude": 37.1003, "longitude": 127.1003},
                    {"cornerOrder": 4, "cornerRole": "BOTTOM_LEFT", "latitude": 37.1004, "longitude": 127.1004}
                  ]
                }
                """.formatted(clientPitchId, name);
    }

    private String invalidCornerRequest(UUID clientPitchId) {
        return """
                {
                  "clientPitchId": "%s",
                  "name": "invalid-corners",
                  "corners": [
                    {"cornerOrder": 1, "cornerRole": "DUPLICATE", "latitude": 37.1, "longitude": 127.1},
                    {"cornerOrder": 1, "cornerRole": "DUPLICATE", "latitude": 37.2, "longitude": 127.2},
                    {"cornerOrder": 3, "cornerRole": "THIRD", "latitude": 37.3, "longitude": 127.3},
                    {"cornerOrder": 4, "cornerRole": "FOURTH", "latitude": 37.4, "longitude": 127.4}
                  ]
                }
                """.formatted(clientPitchId);
    }

    private String duplicateRoleRequest(UUID clientPitchId) {
        return """
                {
                  "clientPitchId": "%s",
                  "name": "duplicate-role",
                  "corners": [
                    {"cornerOrder": 1, "cornerRole": "DUPLICATE", "latitude": 37.1, "longitude": 127.1},
                    {"cornerOrder": 2, "cornerRole": "DUPLICATE", "latitude": 37.2, "longitude": 127.2},
                    {"cornerOrder": 3, "cornerRole": "THIRD", "latitude": 37.3, "longitude": 127.3},
                    {"cornerOrder": 4, "cornerRole": "FOURTH", "latitude": 37.4, "longitude": 127.4}
                  ]
                }
                """.formatted(clientPitchId);
    }
}
