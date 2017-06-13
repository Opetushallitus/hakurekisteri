package fi.vm.sade.hakurekisteri.integration.hakemus;

import com.google.common.base.Optional;
import fi.vm.sade.hakurekisteri.integration.hakemus.dto.SuoritusJaArvosanat;
import fi.vm.sade.hakurekisteri.integration.hakemus.dto.SuoritusJaArvosanatWrapper;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class PohjakoulutusUtil {

    private static final Logger LOG = LoggerFactory.getLogger(PohjakoulutusUtil.class);

    public static final String POHJAKOULUTUS = "POHJAKOULUTUS";

    public static final String ULKOMAINEN_TUTKINTO = "0";
    public static final String PERUSKOULU = "1";
    public static final String OSITTAIN_YKSILOLLISTETTY = "2";
    public static final String ALUEITTAIN_YKSILOLLISTETTY = "3";
    public static final String YKSILOLLISTETTY = "6";
    public static final String KESKEYTYNYT = "7";
    public static final String YLIOPPILAS = "9";

    public static Optional<String> pohjakoulutus(int hakuVuosi, String hakukausiUri, Optional<String> pk, String hakijaOid, List<SuoritusJaArvosanat> suoritukset) {

        // TODO: tätä tarvitaan johinkin (tm)
        // Optional<String> pk = h.getAvaimet().stream()
        //    .filter(a -> POHJAKOULUTUS.equals(a.getAvain()))
        //    .map(AvainArvoDTO::getArvo)
        //    .findFirst();
        if (!pk.isPresent()) {
            return Optional.absent();
        }
        String pohjakoulutusHakemukselta = pk.get();
        List<SuoritusJaArvosanatWrapper> suorituksetRekisterista = new ArrayList<>();
        for (SuoritusJaArvosanat s : suoritukset) {
            suorituksetRekisterista.add(SuoritusJaArvosanatWrapper.wrap(s));
        }

        for (SuoritusJaArvosanatWrapper suoritusJaArvosanatWrapper : suorituksetRekisterista) {
            if ((suoritusJaArvosanatWrapper.isLukio() && suoritusJaArvosanatWrapper.isValmis()) ||
                (suoritusJaArvosanatWrapper.isLukio() && suoritusJaArvosanatWrapper.isKesken() && hakukaudella(hakuVuosi, hakukausiUri, suoritusJaArvosanatWrapper)) ||
                (suoritusJaArvosanatWrapper.isYoTutkinto() && suoritusJaArvosanatWrapper.isVahvistettu() && suoritusJaArvosanatWrapper.isValmis())) {
                return Optional.of(YLIOPPILAS);
            }
        }
        for (SuoritusJaArvosanatWrapper suoritusJaArvosanatWrapper : suorituksetRekisterista) {
            if (suoritusJaArvosanatWrapper.isPerusopetus() && suoritusJaArvosanatWrapper.isVahvistettu() && suoritusJaArvosanatWrapper.isKeskeytynyt()) {
                return Optional.of(KESKEYTYNYT);
            }
        }
        if (YLIOPPILAS.equals(pohjakoulutusHakemukselta)) {
            return Optional.of(YLIOPPILAS);
        }
        Optional<SuoritusJaArvosanatWrapper> perusopetus = Optional.absent();
        for (SuoritusJaArvosanatWrapper suoritusJaArvosanatWrapper : suorituksetRekisterista) {
            if (suoritusJaArvosanatWrapper.isPerusopetus() && suoritusJaArvosanatWrapper.isVahvistettu()) {
                perusopetus = Optional.of(suoritusJaArvosanatWrapper);
                break;
            }
        }
        if (perusopetus.isPresent()) {
            return Optional.of(paattelePerusopetuksenPohjakoulutus(perusopetus.get()));
        }
        Optional<SuoritusJaArvosanatWrapper> perusopetusVahvistamaton = Optional.absent();
        for (SuoritusJaArvosanatWrapper suoritusJaArvosanatWrapper : suorituksetRekisterista) {
            if (suoritusJaArvosanatWrapper.isPerusopetus() && !suoritusJaArvosanatWrapper.isVahvistettu()) {
                perusopetusVahvistamaton = Optional.of(suoritusJaArvosanatWrapper);
                break;
            }
        }
        if (perusopetusVahvistamaton.isPresent()) {
            return Optional.of(paattelePerusopetuksenPohjakoulutus(perusopetusVahvistamaton.get()));
        }
        boolean b = false;
        for (SuoritusJaArvosanatWrapper suoritusJaArvosanatWrapper : suorituksetRekisterista) {
            if (suoritusJaArvosanatWrapper.isUlkomainenKorvaava() && suoritusJaArvosanatWrapper.isVahvistettu() && suoritusJaArvosanatWrapper.isValmis()) {
                b = true;
                break;
            }
        }
        if (b)
            if (PERUSKOULU.equals(pohjakoulutusHakemukselta)) {
                LOG.warn("Hakija {} ilmoittanut peruskoulun, mutta löytyi vahvistettu ulkomainen korvaava suoritus. " + "Käytetään hakemuksen pohjakoulutusta {}.",
                    hakijaOid, pohjakoulutusHakemukselta);
                return Optional.of(pohjakoulutusHakemukselta);
            }
        boolean result = false;
        for (SuoritusJaArvosanatWrapper s : suorituksetRekisterista) {
            if (s.isUlkomainenKorvaava() && s.isVahvistettu() && s.isValmis()) {
                result = true;
                break;
            }
        }
        if (result) {
            return Optional.of(ULKOMAINEN_TUTKINTO);
        } else if (ULKOMAINEN_TUTKINTO.equals(pohjakoulutusHakemukselta)) {
            return Optional.of(ULKOMAINEN_TUTKINTO);
        }
        LOG.warn("Hakijan {} pohjakoulutusta ei voitu päätellä, käytetään hakemuksen pohjakoulutusta {}.",
            hakijaOid, pohjakoulutusHakemukselta);
        return Optional.of(pohjakoulutusHakemukselta);
    }

    private static boolean hakukaudella(int hakuVuosi, String hakukausiUri, SuoritusJaArvosanatWrapper s) {
        DateTime valmistuminen = s.getValmistuminenAsDateTime();
        DateTime kStart = new DateTime(hakuVuosi, 1, 1, 0, 0).minus(1);
        DateTime kEnd = new DateTime(hakuVuosi, 7, 31, 0, 0).plusDays(1);
        DateTime sStart = new DateTime(hakuVuosi, 8, 1, 0, 0).minus(1);
        DateTime sEnd = new DateTime(hakuVuosi, 12, 31, 0, 0).plusDays(1);
        switch (hakukausiUri) {
            case "kausi_k#1":
                return valmistuminen.isAfter(kStart) && valmistuminen.isBefore(kEnd);
            case "kausi_s#1":
                return valmistuminen.isAfter(sStart) && valmistuminen.isBefore(sEnd);
            default:
                throw new RuntimeException(String.format("Tuntematon hakukausi %s", hakukausiUri));
        }
    }

    private static String paattelePerusopetuksenPohjakoulutus(SuoritusJaArvosanatWrapper perusopetus) {
        switch (perusopetus.getSuoritusJaArvosanat().getSuoritus().getYksilollistaminen()) {
            case "Kokonaan":
                return YKSILOLLISTETTY;
            case "Osittain":
                return OSITTAIN_YKSILOLLISTETTY;
            case "Alueittain":
                return ALUEITTAIN_YKSILOLLISTETTY;
            default:
                return PERUSKOULU;
        }
    }
}
