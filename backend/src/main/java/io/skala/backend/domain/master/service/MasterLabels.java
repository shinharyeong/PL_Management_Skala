package io.skala.backend.domain.master.service;

/** 화면 표시용 master 이름. 월간 master_ver{월}.{NN}, 연간 master_plan{연도}.{NN} */
public final class MasterLabels {

    private MasterLabels() {
    }

    public static String monthly(int month, int seq) {
        return "master_ver%d.%02d".formatted(month, seq);
    }

    public static String annual(int year, int seq) {
        return "master_plan%d.%02d".formatted(year, seq);
    }
}
