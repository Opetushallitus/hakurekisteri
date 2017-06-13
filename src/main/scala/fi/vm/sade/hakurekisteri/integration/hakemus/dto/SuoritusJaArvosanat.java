package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.commons.collections.comparators.BooleanComparator;
import org.joda.time.DateTime;

import java.util.List;
import java.util.Map;

public class SuoritusJaArvosanat implements Comparable<SuoritusJaArvosanat> {

    private static final Map<String, Integer> tilaToPrioriteetti = ImmutableMap.of(
        "VALMIS", 1,
        "KESKEN", 2,
        "KESKEYTYNYT", 3
    );

    private Suoritus suoritus;
    private List<Arvosana> arvosanat = Lists.newArrayList();

    public SuoritusJaArvosanat(Suoritus suoritus, List<Arvosana> arvosanat) {
        this.suoritus = suoritus;
        this.arvosanat = arvosanat;
    }

    public List<Arvosana> getArvosanat() {
        return arvosanat;
    }

    public void setArvosanat(List<Arvosana> arvosanat) {
        this.arvosanat = arvosanat;
    }

    public Suoritus getSuoritus() {
        return suoritus;
    }

    public void setSuoritus(Suoritus suoritus) {
        this.suoritus = suoritus;
    }

    @Override
    public int compareTo(SuoritusJaArvosanat o) {
        final int vahvistettu = BooleanComparator
                .getTrueFirstComparator()
                .compare(suoritus.isVahvistettu(), o.getSuoritus().isVahvistettu());

        if (vahvistettu == 0) {
            final int tila = tilaToPrioriteetti.get(suoritus.getTila()).compareTo(tilaToPrioriteetti.get(o.getSuoritus().getTila()));
            if (tila == 0) {
                final DateTime current = ArvosanaWrapper.ARVOSANA_DTF.parseDateTime(suoritus.getValmistuminen());
                final DateTime oDate = ArvosanaWrapper.ARVOSANA_DTF.parseDateTime(o.getSuoritus().getValmistuminen());
                return oDate.compareTo(current);
            } else {
                return tila;
            }
        } else {
            return vahvistettu;
        }

    }
}
