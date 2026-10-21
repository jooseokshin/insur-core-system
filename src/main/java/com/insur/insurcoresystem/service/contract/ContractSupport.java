package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

/**
 * 계약 업무 서비스들이 함께 쓰는 규칙: 사번 정규화, 접수자 조회 범위, 페이징 검증, 계약 없음 오류.
 */
public final class ContractSupport {

    /** 페이징 조회의 기본 페이지 크기와 최대 페이지 크기 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /** 사번 컬럼(REG_EMP_ID, EMP_ID)의 최대 길이 */
    private static final int EMP_ID_MAX_LENGTH = 64;

    private ContractSupport() {
    }

    /** 사번을 컬럼 길이(64)에 맞춘다. 저장할 때와 접수자 제한을 비교할 때 같은 규칙을 쓰도록 이 메서드 하나만 사용한다. */
    public static String normalizeEmpId(String empId) {
        // Spring 의 StringUtils.truncate 는 잘린 뒤에 " (truncated)..." 를 덧붙여 컬럼 길이를 넘기므로 직접 자른다
        return empId == null || empId.length() <= EMP_ID_MAX_LENGTH ? empId : empId.substring(0, EMP_ID_MAX_LENGTH);
    }

    /** 접수자 제한이 없거나(null) 계약의 접수 사번이 제한 사번과 같으면 조회할 수 있다. */
    public static boolean isReadable(Contract contract, String ownerEmpId) {
        return ownerEmpId == null || normalizeEmpId(ownerEmpId).equals(contract.getRegEmpId());
    }

    /**
     * 데이터 무결성 오류가 이름이 {@code constraint} 로 끝나는 제약 위반인지 판단한다. 오류 메시지 문장이 아니라 Hibernate 가 DB 오류에서
     * 뽑아 둔 제약명({@link ConstraintViolationException#getConstraintName()})을 소문자로 바꿔 끝부분만 비교하므로(스키마가 붙을 수 있다)
     * DB 메시지 형식이나 언어 설정에 영향받지 않는다. 제약명을 알 수 없으면 false 다.
     */
    public static boolean isConstraintViolation(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase().endsWith(constraint);
            }
        }
        return false;
    }

    /** 계약이 없을 때와 조회 권한 밖일 때 똑같이 쓰는 404 오류. 계약의 존재 여부를 구분할 수 없게 한다. */
    public static BusinessException contractNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "E-CTR-010", "해당 계약이 존재하지 않습니다.");
    }

    /**
     * 페이지 번호와 크기를 검증해 조회용 PageRequest 를 만든다. 생략하면 0페이지, 기본 크기를 쓴다.
     *
     * @throws BusinessException 페이지 번호가 음수이거나 크기가 1~{@value #MAX_PAGE_SIZE} 범위를 벗어난 경우(E-CTR-016)
     */
    public static PageRequest pageRequest(Integer page, Integer size, Sort sort) {
        int pageNo = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNo < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException("E-CTR-016",
                    "페이지 번호는 0 이상, 페이지 크기는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.");
        }
        return PageRequest.of(pageNo, pageSize, sort);
    }

    /** 거래 UUID 가 비었거나 컬럼 길이(64)를 넘으면 업무 오류(E-CTR-008)로 처리한다. */
    public static void validateGlobalTxId(String globalTxId) {
        if (isInvalidCode(globalTxId, 64)) {
            throw new BusinessException("E-CTR-008", "거래 UUID(globalTxId)는 필수이며 64자 이하여야 합니다.");
        }
    }
}
