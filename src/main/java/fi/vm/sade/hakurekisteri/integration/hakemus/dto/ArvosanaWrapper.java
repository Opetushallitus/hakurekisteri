package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class ArvosanaWrapper {
    private final static Logger LOG = LoggerFactory.getLogger(ArvosanaWrapper.class);
    private final Arvosana arvosana;
    private final List<String> SAAME = Arrays.asList("IS", "ZA", "QS");

    private final Set<String> KOERYHMA_94_VIERAAT_KIELET = Sets.newHashSet(
            "CC", "DC", "EA", "EB", "EC", "FA", "FB", "FC", "GA", "GB", "GC", "HA", "HB", "IC", "QC", "KC", "L1", "L7",
            "PA", "PB", "PC", "SA", "SB", "SC", "S9", "TA", "TB", "TC", "VA", "VB", "VC", "E1", "E2", "F1", "F2", "G1",
            "G2", "H1", "H2", "P1", "P2", "S1", "S2", "T1", "T2", "V1", "V2"
    );

    private final Set<String> KOERYHMA_91_AIDINKIELI = Sets.newHashSet(
            "O", "A", "I", "J", "Z", "W");
    private final Set<String> KOERYHMA_92_SUOMIRUOTSI_TOISENA_KIELENA = Sets.newHashSet(
            "O5", "A5");
    private final Set<String> KOERYHMA_93_TOINEN_KOTIMAINEN_KIELI = Sets.newHashSet(
            "BA", "BB", "CA", "CB");

    private final Set<String> KOERYHMA_95_MATEMATIIKKA = Sets.newHashSet(
            "M", "N");
    private final Set<String> KOERYHMA_96_REAALI = Sets.newHashSet(
            "RR", "RO", "RY", "UE", "UO", "ET", "FF", "PS", "HI", "FY", "KE", "BI", "GE", "TE", "YH"
    );
    public static final org.joda.time.format.DateTimeFormatter ARVOSANA_DTF = DateTimeFormat.forPattern("dd.MM.yyyy");

    public ArvosanaWrapper(Arvosana arvosana) {
        this.arvosana = arvosana;
    }

    public boolean isVieraskieli() {
        return KOERYHMA_94_VIERAAT_KIELET.contains(arvosana.getAine());
    }

    public boolean isAidinkieli() {
        return KOERYHMA_91_AIDINKIELI.contains(arvosana.getAine());
    }

    public boolean isSuomiRuotsiToisenaKielena() {
        return KOERYHMA_92_SUOMIRUOTSI_TOISENA_KIELENA.contains(arvosana.getAine());
    }

    public boolean isMatematiikka() {
        return KOERYHMA_95_MATEMATIIKKA.contains(arvosana.getAine());
    }

    public boolean isReaali() {
        return KOERYHMA_96_REAALI.contains(arvosana.getAine());
    }

    public boolean isToinenKotimainenKieli() {
        return KOERYHMA_93_TOINEN_KOTIMAINEN_KIELI.contains(arvosana.getAine());
    }

    public boolean isSaame() {
        return SAAME.contains(arvosana.getLisatieto());
    }

    public boolean isEnglanti() {
        return "J".equals(arvosana.getAine());
    }

    public DateTime getMyonnettyAsDateTime() {
        if (arvosana.getMyonnetty() == null) {
            LOG.error("Arvosanalla ei ole myöntämispäivämäärää: {}", new Gson().toJson(arvosana));
            throw new RuntimeException("Arvosanalla ei ole myöntämispäivämäärää");
        }
        return ARVOSANA_DTF.parseDateTime(arvosana.getMyonnetty());
    }

    public boolean onkoMyonnettyEnnen(DateTime referenssiPvm) {
        if (referenssiPvm == null) {
            return true;
        }
        return referenssiPvm.isAfter(getMyonnettyAsDateTime());
    }

    public Arvosana getArvosana() {
        return arvosana;
    }
}
