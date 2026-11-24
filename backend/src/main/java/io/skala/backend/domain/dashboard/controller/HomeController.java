package io.skala.backend.domain.dashboard.controller;

import io.skala.backend.domain.dashboard.dto.HomeDivisionsResponse;
import io.skala.backend.domain.dashboard.dto.HomeOpDriversResponse;
import io.skala.backend.domain.dashboard.dto.HomePnlResponse;
import io.skala.backend.domain.dashboard.dto.HomeStatusResponse;
import io.skala.backend.domain.dashboard.dto.HomeTodoResponse;
import io.skala.backend.domain.dashboard.dto.HomeWaterfallResponse;
import io.skala.backend.domain.dashboard.service.HomePnlQueryService;
import io.skala.backend.domain.dashboard.service.HomeQueryService;
import io.skala.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HOME-01 마감 현황, HOME-02 오늘 할 일, HOME-03~06 전사 손익 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/home")
public class HomeController {

    private final HomeQueryService homeService;
    private final HomePnlQueryService pnlService;
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

    @GetMapping("/pnl")
    public HomePnlResponse pnl(@RequestParam(required = false) String period, @RequestParam int year,
                               @RequestParam(required = false) Integer month, @RequestParam(required = false) Long masterId) {
        return pnlService.pnl(currentUser.get(), period, year, month, masterId);
    }

    @GetMapping("/op-drivers")
    public HomeOpDriversResponse opDrivers(@RequestParam(required = false) String period, @RequestParam int year,
                                           @RequestParam(required = false) Integer month,
                                           @RequestParam(required = false) Long masterId,
                                           @RequestParam(required = false) Integer limit) {
        return pnlService.opDrivers(currentUser.get(), period, year, month, masterId, limit);
    }

    @GetMapping("/divisions")
    public HomeDivisionsResponse divisions(@RequestParam(required = false) String period, @RequestParam int year,
                                           @RequestParam(required = false) Integer month,
                                           @RequestParam(required = false) Long masterId) {
        return pnlService.divisions(currentUser.get(), period, year, month, masterId);
    }

    @GetMapping("/waterfall")
    public HomeWaterfallResponse waterfall(@RequestParam(required = false) String period, @RequestParam int year,
                                           @RequestParam(required = false) Integer month,
                                           @RequestParam(required = false) Long masterId) {
        return pnlService.waterfall(currentUser.get(), period, year, month, masterId);
    }
}
