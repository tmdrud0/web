package my.oj.web.user;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.auth.CurrentUser;
import my.oj.web.user.User;
import my.oj.web.user.dto.UserDto;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/login")
    public String showLogin() {
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String userName,
                          @RequestParam String pass,
                          HttpSession session,
                          Model model) {
        return userService.findByCredentials(userName, pass)
                .map(user -> {
                    session.setAttribute("user", UserDto.from(user));
                    return "redirect:/problems";
                })
                .orElseGet(() -> {
                    model.addAttribute("error", "Invalid credentials.");
                    return "login";
                });
    }

    @GetMapping("/register")
    public String showRegister() {
        return "register";
    }

    @PostMapping("/register")
    public String doRegister(@RequestParam String userName,
                             @RequestParam String pass,
                             Model model) {
        if (userService.isUserExists(userName, pass)) {
            model.addAttribute("error", "User already exists.");
            return "register";
        }
        userService.register(userName, pass);
        return "redirect:/login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @GetMapping("/profile/{id}")
    public String showUserProfile(@PathVariable Long id,
                                  @CurrentUser UserDto loggedInUser,
                                  Model model) {
        User user = userService.findById(id).orElse(null);
        if (user == null) {
            return "redirect:/";
        }
        model.addAttribute("profileUser", UserDto.from(user));
        return "profile";
    }
}


