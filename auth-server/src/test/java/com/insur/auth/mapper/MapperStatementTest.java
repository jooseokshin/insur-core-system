package com.insur.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매퍼 인터페이스와 XML 이 어긋나지 않았는지 확인한다 (기동 시 스키마 검증이 없으므로 시험으로 대신한다).
 */
@SpringBootTest
@Transactional
class MapperStatementTest {

    @Autowired
    SqlSessionFactory sqlSessionFactory;
    @Autowired
    EmployeeMapper employeeMapper;

    @Test
    void 매퍼_인터페이스의_모든_메서드에_XML_문장이_있다() {
        var configuration = sqlSessionFactory.getConfiguration();
        List<String> missing = new ArrayList<>();
        for (Class<?> mapper : configuration.getMapperRegistry().getMappers()) {
            for (Method method : mapper.getDeclaredMethods()) {
                if (method.isDefault() || method.isSynthetic()) {
                    continue;
                }
                String id = mapper.getName() + "." + method.getName();
                if (!configuration.hasStatement(id, false)) {
                    missing.add(id);
                }
            }
        }
        assertThat(configuration.getMapperRegistry().getMappers()).isNotEmpty();
        assertThat(missing).as("XML 에 문장이 없는 매퍼 메서드").isEmpty();
    }

    @Test
    void 사원_수를_센다() {
        assertThat(employeeMapper.count()).isGreaterThanOrEqualTo(0);
    }
}
