package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

public class SuoritusJaArvosanatWrapper {
    private static final Logger LOG = LoggerFactory.getLogger(SuoritusJaArvosanatWrapper.class);
    public static final String YO_KOMO = "1.2.246.562.5.2013061010184237348007";
    public static final String PK_KOMO = "1.2.246.562.13.62959769647";
    public static final String PK_10_KOMO = "1.2.246.562.5.2013112814572435044876";
    public static final String LK_KOMO = "TODO lukio komo oid";
    public static final String AM_KOMO = "TODO ammatillinen komo oid";
    public static final String VALMA_KOMO = "valma";
    public static final String TELMA_KOMO = "telma";
    public static final String PK_LISAOPETUSTALOUS_KOMO = "1.2.246.562.5.2013061010184614853416";
    public static final String PK_AMMATTISTARTTI_KOMO = "1.2.246.562.5.2013112814572438136372";
    public static final String PK_VALMENTAVA = "1.2.246.562.5.2013112814572435755085";
    public static final String PK_AMMATILLISEENVALMISTAVA = "1.2.246.562.5.2013112814572441001730";
    public static final String ULKOMAINENKORVAAVA = "1.2.246.562.13.86722481404";
    public static final String PK_LUKIOON_VALMISTAVA = "1.2.246.562.5.2013112814572429142840";
    public static final String AMMATILLISEN_KIELIKOE = "ammatillisenKielikoe";
    private static final Map<String, String> KOMO_TO_STRING_MAPPER = createKomoToStringMapper();
    public static final String HAKEMUS_OID_PREFIX = "1.2.246.562.11";

    public static final String SUORITUS_PVM_FORMAT = "dd.MM.yyyy";
    public static final DateTimeFormatter ARVOSANA_PVM_FORMATTER =
            DateTimeFormat.forPattern(SuoritusJaArvosanatWrapper.SUORITUS_PVM_FORMAT);

    private static Map<String, String> createKomoToStringMapper() {
        Map<String, String> tmp = Maps.newHashMap();
        tmp.put(YO_KOMO, "YO-suoritus");
        tmp.put(PK_KOMO, "perusopetussuoritus");
        tmp.put(PK_10_KOMO, "lisäopetussuoritus");
        tmp.put(LK_KOMO, "lukiosuoritus");
        tmp.put(AM_KOMO, "ammatillinen suoritus");
        tmp.put(PK_AMMATILLISEENVALMISTAVA, "ammatilliseen valmistava");
        tmp.put(ULKOMAINENKORVAAVA, "ulkomainen korvaava");
        tmp.put(PK_VALMENTAVA, "valmentava");
        tmp.put(PK_AMMATTISTARTTI_KOMO, "ammattistartti");
        tmp.put(PK_LISAOPETUSTALOUS_KOMO, "lisäopetus(talous)");
        tmp.put(AMMATILLISEN_KIELIKOE, "ammatillisen kielikoe");
        return Collections.unmodifiableMap(tmp);
    }

    private final SuoritusJaArvosanat suoritusJaArvosanat;
    public static final org.joda.time.format.DateTimeFormatter VALMISTUMIS_DTF = DateTimeFormat.forPattern("dd.MM.yyyy");

    public DateTime getValmistuminenAsDateTime() {
        if (suoritusJaArvosanat.getSuoritus().getValmistuminen() == null) {
            LOG.error("Suorituksella ei ole valmistumispäivämäärää: {}", new Gson().toJson(suoritusJaArvosanat.getSuoritus()));
            throw new RuntimeException("Suorituksella ei ole valmistumispäivämäärää");
        }
        return VALMISTUMIS_DTF.parseDateTime(suoritusJaArvosanat.getSuoritus().getValmistuminen());
    }

    public static SuoritusJaArvosanatWrapper wrap(SuoritusJaArvosanat s) {
        return new SuoritusJaArvosanatWrapper(s);
    }

    public boolean isVahvistettu() {
        return suoritusJaArvosanat.getSuoritus().isVahvistettu();
    }

    public boolean isItseIlmoitettu() {
        if (suoritusJaArvosanat.getSuoritus().getHenkiloOid() != null) {
            return suoritusJaArvosanat.getSuoritus().getHenkiloOid().equals(suoritusJaArvosanat.getSuoritus().getSource());
        } else {
            return false;
        }
    }

    public boolean onHakemukselta() {
        return StringUtils.defaultString(suoritusJaArvosanat.getSuoritus().getMyontaja()).startsWith(HAKEMUS_OID_PREFIX);
    }

    public boolean onTaltaHakemukselta(String  hakemusOid) {
        return onHakemukselta() && suoritusJaArvosanat.getSuoritus().getMyontaja().equals(hakemusOid);
    }

    public boolean isValmis() {
        return "VALMIS".equals(suoritusJaArvosanat.getSuoritus().getTila());
    }

    public boolean isKesken() {
        return "KESKEN".equals(suoritusJaArvosanat.getSuoritus().getTila());
    }

    public boolean isKeskeytynyt() {
        return "KESKEYTYNYT".equals(suoritusJaArvosanat.getSuoritus().getTila());
    }

    public String komoToString() {
        return Optional.fromNullable(KOMO_TO_STRING_MAPPER.get(suoritusJaArvosanat.getSuoritus().getKomo())).or("Tuntematon suoritus " + suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public SuoritusJaArvosanatWrapper(SuoritusJaArvosanat suoritusJaArvosanat) {
        this.suoritusJaArvosanat = suoritusJaArvosanat;
    }

    public SuoritusJaArvosanat getSuoritusJaArvosanat() {
        return suoritusJaArvosanat;
    }

    public boolean isPerusopetus() {
        return PK_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isAmmatillinen() {
        return AM_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isLukio() {
        return LK_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isYoTutkinto() {
        return YO_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isLisaopetus() {
        return PK_10_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isLisaopetusTalous() {
        return PK_LISAOPETUSTALOUS_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isAmmattistartti() {
        return PK_AMMATTISTARTTI_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isValmentava() {
        return PK_VALMENTAVA.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isAmmatilliseenValmistava() {
        return PK_AMMATILLISEENVALMISTAVA.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isLukioonValmistava() {
        return PK_LUKIOON_VALMISTAVA.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isUlkomainenKorvaava() {
        return ULKOMAINENKORVAAVA.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }
    public boolean isValmaOrTelma() {
        return VALMA_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo()) || TELMA_KOMO.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isAmmatillisenKielikoe() {
        return isAmmatillisenKielikoe(this.suoritusJaArvosanat);
    }

    public static boolean isAmmatillisenKielikoe(SuoritusJaArvosanat suoritusJaArvosanat) {
        return AMMATILLISEN_KIELIKOE.equals(suoritusJaArvosanat.getSuoritus().getKomo());
    }

    public boolean isSuoritusMistaSyntyyPeruskoulunArvosanoja() {
        return isLisapistekoulutus() || isPerusopetus();
    }

    public boolean isLisapistekoulutus() {
        return isLisaopetus() ||
                isLisaopetusTalous() ||
                isValmaOrTelma() ||
                isAmmattistartti() ||
                isValmentava() ||
                isAmmatilliseenValmistava() ||
                isLukioonValmistava();
    }
}
