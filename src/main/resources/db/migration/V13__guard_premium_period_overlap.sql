-- 요율/요율 계수의 적용 기간이 겹치지 않도록 DB 에서 막는다. (개정은 기존 행의 EFF_TO 를 먼저 닫고 새 행을 추가해야 한다)
-- 확장(btree_gist) 없이 쓸 수 있도록 제약 대신 트리거로 겹침을 검사한다. 적용 기간은 양 끝 포함이고 EFF_TO 가 NULL 이면 끝이 없다.

CREATE FUNCTION FN_PRM_RATE_NO_OVERLAP() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM TB_PRM_RATE r
                WHERE r.INSR_CLS_CD = NEW.INSR_CLS_CD AND r.CVRG_CD = NEW.CVRG_CD
                  AND r.RATE_ID IS DISTINCT FROM NEW.RATE_ID
                  AND daterange(r.EFF_FROM, r.EFF_TO, '[]') && daterange(NEW.EFF_FROM, NEW.EFF_TO, '[]')) THEN
        RAISE EXCEPTION '요율 적용 기간이 겹칩니다: 보험종목=%, 담보=%, 시작일=%', NEW.INSR_CLS_CD, NEW.CVRG_CD, NEW.EFF_FROM
            USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER TRG_PRM_RATE_NO_OVERLAP BEFORE INSERT OR UPDATE ON TB_PRM_RATE
    FOR EACH ROW EXECUTE FUNCTION FN_PRM_RATE_NO_OVERLAP();

-- 계수는 같은 보험종목/계수 종류에서 (같은 키) 또는 (겹치는 구간)이면서 적용 기간이 겹치는 행이 있으면 안 된다.
CREATE FUNCTION FN_PRM_FACTOR_NO_OVERLAP() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM TB_PRM_FACTOR f
                WHERE f.INSR_CLS_CD = NEW.INSR_CLS_CD AND f.FACTOR_TYPE = NEW.FACTOR_TYPE
                  AND f.FACTOR_ID IS DISTINCT FROM NEW.FACTOR_ID
                  AND daterange(f.EFF_FROM, f.EFF_TO, '[]') && daterange(NEW.EFF_FROM, NEW.EFF_TO, '[]')
                  AND ((f.FACTOR_KEY IS NOT NULL AND f.FACTOR_KEY = NEW.FACTOR_KEY)
                       OR (f.RANGE_FROM IS NOT NULL AND NEW.RANGE_FROM IS NOT NULL
                           AND int4range(f.RANGE_FROM, f.RANGE_TO, '[]') && int4range(NEW.RANGE_FROM, NEW.RANGE_TO, '[]')))) THEN
        RAISE EXCEPTION '요율 계수 적용 기간이 겹칩니다: 보험종목=%, 계수=%, 시작일=%', NEW.INSR_CLS_CD, NEW.FACTOR_TYPE, NEW.EFF_FROM
            USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER TRG_PRM_FACTOR_NO_OVERLAP BEFORE INSERT OR UPDATE ON TB_PRM_FACTOR
    FOR EACH ROW EXECUTE FUNCTION FN_PRM_FACTOR_NO_OVERLAP();
