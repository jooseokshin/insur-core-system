package com.insur.insurcoresystem.ext.kcis;

import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.an;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.n;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.text;

import com.insur.insurcoresystem.ext.FixedLengthCodec.Field;
import java.util.List;

/**
 * 한국신용정보원 연계 전문 배치. <b>실제 기관 규격이 아니다.</b> 실제 규격은 기관과 맺은 연계 명세서로만 받을 수 있어서,
 * 여기서는 실무 전문과 같은 구조(공통부 + 개별부, 바이트 고정길이, 응답코드, 추적번호, 반복부)로 이 프로젝트용 배치를 정의했다.
 * 실제 연계로 바꿀 때는 이 클래스의 항목만 명세서대로 바꾸면 되도록 배치를 한곳에 모았다.
 *
 * <pre>
 * 공통부 60바이트: 전문길이(4) 전문종별(4) 거래구분(4) 기관코드(3) 거래일시(14) 추적번호(20) 응답코드(4) 예비(7)
 *   전문종별 0200 요청 / 0210 응답, 응답코드 0000 정상
 * Q001 실손 계약 조회: 요청 피보험자식별(20) 기준일(8) / 응답 건수(2) + 건수 × 계약(73)
 * R001 실손 계약정보 집중: 변경구분(1) 증권번호(30) 피보험자식별(20) 계약구분(2) 보장시작(8) 보장종료(8) 급여(1) 비급여(1) 상품명(30)
 * R002 실손 지급정보 집중: 사고번호(30) 증권번호(30) 피보험자식별(20) 최초진료일(8) 급여지급액(13) 비급여지급액(13) 지급일(8)
 * </pre>
 */
public final class KcisLayouts {

    public static final String AGENCY = "KCIS";
    public static final String REQUEST = "0200";
    public static final String RESPONSE = "0210";
    public static final String OK = "0000";
    /** 거래구분: 실손 계약 조회 (중복가입 확인, 비례보상) */
    public static final String Q001 = "Q001";
    /** 거래구분: 실손 계약정보 집중 (성립·해지) */
    public static final String R001 = "R001";
    /** 거래구분: 실손 보험금 지급정보 집중 */
    public static final String R002 = "R002";

    public static final List<Field> HEADER = List.of(
            n("length", 4), an("kind", 4), an("txType", 4), an("orgCode", 3), n("txDateTime", 14),
            an("traceNo", 20), an("responseCode", 4), an("reserved", 7));

    public static final List<Field> Q001_REQUEST = List.of(an("insuredId", 20), n("baseDate", 8));
    public static final List<Field> Q001_RESPONSE_COUNT = List.of(n("count", 2));
    public static final List<Field> Q001_RESPONSE_ROW = List.of(
            an("insurerCode", 3), an("policyNo", 30), an("kind", 2), n("startDate", 8), n("endDate", 8),
            an("coveredYn", 1), an("nonCoveredYn", 1), text("productName", 20));

    public static final List<Field> R001_BODY = List.of(
            an("change", 1), an("policyNo", 30), an("insuredId", 20), an("kind", 2), n("startDate", 8), n("endDate", 8),
            an("coveredYn", 1), an("nonCoveredYn", 1), text("productName", 30));

    public static final List<Field> R002_BODY = List.of(
            an("claimNo", 30), an("policyNo", 30), an("insuredId", 20), n("firstTreatDate", 8),
            n("coveredPaid", 13), n("nonCoveredPaid", 13), n("paidDate", 8));

    /** 조회 응답 한 건의 최대 건수 (2자리) */
    public static final int MAX_ROWS = 99;

    private KcisLayouts() {
    }
}
