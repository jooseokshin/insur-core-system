package com.insur.insurcoresystem.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.insur.insurcoresystem.mapper.cms.CmsMapper;
import com.insur.insurcoresystem.mapper.medical.MedicalMapper;
import com.insur.insurcoresystem.mapper.premium.PremiumRateMapper;
import com.insur.insurcoresystem.mapper.product.ProductMapper;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매퍼 인터페이스와 XML 이 어긋나지 않았는지, 그리고 다른 시험이 실행하지 않는 문장이 실제 DB 에서 도는지 확인한다.
 * JPA 의 기동 시 스키마 검증(ddl-auto=validate)이 없어졌으므로, 문장은 실행해 봐야 컬럼 이름 오류가 드러난다.
 */
@SpringBootTest
@Transactional
class MapperStatementTest {

    @Autowired
    SqlSessionFactory sqlSessionFactory;
    @Autowired
    ProductMapper productMapper;
    @Autowired
    PremiumRateMapper premiumRateMapper;
    @Autowired
    MedicalMapper medicalMapper;
    @Autowired
    CmsMapper cmsMapper;

    @Test
    void 매퍼_인터페이스의_모든_메서드에_XML_문장이_있다() {
        var configuration = sqlSessionFactory.getConfiguration();
        List<String> missing = new ArrayList<>();
        for (Class<?> mapper : configuration.getMapperRegistry().getMappers()) {
            for (Method method : mapper.getDeclaredMethods()) {
                if (method.isDefault() || method.isSynthetic()) {
                    continue;   // default 메서드는 다른 문장을 묶어 부르는 자바 코드다
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
    void 빈_목록으로_조회하면_문법_오류가_아니라_빈_결과다() {
        assertThat(productMapper.findAllById(Set.of())).isEmpty();
        assertThat(premiumRateMapper.findEffective("01", List.of(), LocalDate.of(2026, 1, 1))).isEmpty();
    }

    @Test
    void 상품을_코드_여러_개로_조회하면_담보_구성도_함께_온다() {
        var products = productMapper.findAllById(Set.of("CAR-STD-2026", "MED-STD-2026", "NO-SUCH"));

        assertThat(products).extracting("prdCd").containsExactly("CAR-STD-2026", "MED-STD-2026");
        assertThat(products.get(0).getCoverages()).isNotEmpty();
    }

    @Test
    void 동의_기록이_없는_계약의_동의_일시는_빈_값이다() {
        assertThat(medicalMapper.findConsentDt("NO-SUCH-CONTRACT")).isEmpty();
    }

    @Test
    void 자동이체_하루_현황_조회_문장이_실행된다() {
        LocalDate day = LocalDate.of(1999, 1, 1);
        assertThat(cmsMapper.findDayDebits(day)).isEmpty();
        assertThat(cmsMapper.findDayFiles(day)).isEmpty();
    }
}
