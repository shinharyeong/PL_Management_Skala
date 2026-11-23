package io.skala.backend.domain.dashboard.controller;

import io.skala.backend.domain.dashboard.dto.HomeStatusResponse;
import io.skala.backend.domain.dashboard.dto.HomeTodoResponse;
import io.skala.backend.domain.dashboard.service.HomeQueryService;
import io.skala.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HOME-01 마감 현황, HOME-02 오늘 할 일 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/home")
public class HomeController {

    private final HomeQueryService homeService;
    private final CurrentUser currentUser;

    @GetMapping("/status")
    public HomeStatusResponse status(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month) {
        return homeService.status(currentUser.get(), year, month);
    }

    @GetMapping("/todos")
    public HomeTodoResponse todos(@RequestParam String tab, @RequestParam(required = false) Integer year,
                                  @RequestParam(required = false) Integer month) {
        return homeService.todos(currentUser.get(), tab, year, month);
    }
}
