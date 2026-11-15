package com.insur.insurcoresystem.mapper.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * 계약 목록 검색 조건. null 인 항목은 조건에서 빠진다.
 *
 * @param regEmpId        접수 사번 (본인 계약만 보는 호출자)
 * @param contractNoLike  증권번호 앞부분 LIKE 패턴 (이스케이프 문자 {@code \})
 * @param applNoLike      청약번호 앞부분 LIKE 패턴
 * @param sysRegFrom      등록일시 하한 (포함)
 * @param sysRegTo        등록일시 상한 (미포함)
 * @param sortColumn      정렬 컬럼 (서비스의 허용 목록 값)
 * @param sortDir         ASC 또는 DESC
 */
@Builder
public record ContractSearchCondition(String regEmpId, String contractNoLike, String applNoLike, String insrClsCd,
                                      String ctrStatCd, String policyholderId, String insuredId,
                                      LocalDate startDateFrom, LocalDate startDateTo,
                                      BigDecimal premiumMin, BigDecimal premiumMax,
                                      LocalDateTime sysRegFrom, LocalDateTime sysRegTo,
                                      String sortColumn, String sortDir) {
}
