package com.insur.insurcoresystem.support;

import java.util.function.Consumer;

/** 시험 데이터 준비용: 매퍼의 insert 를 부르고 (생성 키가 채워진) 그 행을 돌려준다. */
public final class TestRows {

    private TestRows() {
    }

    public static <T> T insert(Consumer<T> inserter, T row) {
        inserter.accept(row);
        return row;
    }
}
