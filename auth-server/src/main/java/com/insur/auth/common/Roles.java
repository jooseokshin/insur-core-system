package com.insur.auth.common;

import java.util.Set;

/**
 * 역할 이름. 코어 백엔드의 Roles 와 같은 값이며, 발급하는 JWT 의 roles 클레임에 담긴다.
 */
public final class Roles {

    public static final String AGENT = "AGENT";
    public static final String UNDERWRITER = "UNDERWRITER";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";

    /** 부여할 수 있는 모든 역할 */
    public static final Set<String> ALL = Set.of(AGENT, UNDERWRITER, OPERATOR, ADMIN);

    private Roles() {
    }
}
