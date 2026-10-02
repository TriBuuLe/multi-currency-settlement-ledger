package com.tribule.ledger.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the visual walkthrough at {@code /demo}. The page itself is static
 * ({@code static/demo.html}) and drives the real API, so it shows what the ledger
 * actually did rather than a recording of it.
 */
@Controller
@Hidden
public class DemoController {

    @GetMapping("/demo")
    public String demo() {
        return "redirect:/demo.html";
    }
}
