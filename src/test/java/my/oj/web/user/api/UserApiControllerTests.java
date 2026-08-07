package my.oj.web.user.api;

import my.oj.web.user.User;
import my.oj.web.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserApiController.class)
class UserApiControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;

    @Test
    void registeringReturnsCreatedWithTheNewUser() throws Exception {
        User created = user(5L, "alice");
        given(userService.isUserExists("alice", "pw")).willReturn(false);
        given(userService.register("alice", "pw")).willReturn(created);

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"alice\",\"pass\":\"pw\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("alice"));
    }

    /**
     * 409 rather than the re-rendered form with an error string in the model. The request was
     * well-formed; it lost a race with an existing name.
     */
    @Test
    void registeringATakenNameIsAConflict() throws Exception {
        given(userService.isUserExists("alice", "pw")).willReturn(true);

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"alice\",\"pass\":\"pw\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("UserAlreadyExists"));

        verify(userService).isUserExists("alice", "pw");
    }

    @Test
    void aBlankRegistrationIsRejectedBeforeTheServiceIsCalled() throws Exception {
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"\",\"pass\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MethodArgumentNotValidException"));

        verifyNoInteractions(userService);
    }

    @Test
    void signingOutIsAPostAndAnswersNoContent() throws Exception {
        mockMvc.perform(post("/api/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    void readingAnUnknownUserIsNotFound() throws Exception {
        given(userService.findById(any())).willReturn(Optional.empty());

        mockMvc.perform(get("/api/users/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("UserNotFoundException"));
    }

    private static User user(long id, String name) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getName()).thenReturn(name);
        return user;
    }
}
