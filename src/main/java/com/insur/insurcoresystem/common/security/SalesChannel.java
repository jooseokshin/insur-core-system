package com.insur.insurcoresystem.common.security;

import com.insur.insurcoresystem.common.exception.BusinessException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 판매채널코드 (계약 원장 SALES_CHNL_CD). 계약이 어느 경로로 들어왔는지이며, 채널별 할인 계수(SALES_CHANNEL)의 키이기도 하다.
 * <ul>
 *     <li>10 대면: 설계사·직원이 업무 화면으로 접수 (사람 토큰)</li>
 *     <li>20 다이렉트(CM): 홈페이지·앱 채널계 시스템</li>
 *     <li>30 TM: 텔레마케팅 채널계 시스템</li>
 * </ul>
 * 채널은 요청 바디가 아니라 토큰으로 정한다. 채널 시스템 토큰에는 인증 서버가 등록 정보의 channel_cd 클레임을 넣는다.
 * 클라이언트가 바디로 채널을 보내 할인을 받을 수 없게 하려는 것이다.
 */
public final class SalesChannel {

    public static final String FACE_TO_FACE = "10";
    public static final String DIRECT = "20";
    public static final String TELEMARKETING = "30";
    /** 채널 시스템 토큰의 채널 클레임 이름 */
    public static final String CLAIM = "channel_cd";

    private static final Set<String> SYSTEM_CHANNELS = Set.of(DIRECT, TELEMARKETING);

    private SalesChannel() {
    }

    /**
     * 요청한 주체의 판매채널. 채널 시스템(CHANNEL 역할)이면 토큰의 channel_cd, 사람이면 대면(10).
     *
     * @throws BusinessException CHANNEL 역할인데 채널 클레임이 없거나 알 수 없는 값인 경우(E-AUTH-002, 403)
     */
    public static String of(Authentication authentication) {
        boolean channelSystem = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + Roles.CHANNEL).equals(a.getAuthority()));
        if (!channelSystem) {
            return FACE_TO_FACE;
        }
        String code = authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString(CLAIM) : null;
        if (code == null || !SYSTEM_CHANNELS.contains(code)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "E-AUTH-002", "채널 시스템 토큰에 판매채널 정보가 없습니다.");
        }
        return code;
    }
}
