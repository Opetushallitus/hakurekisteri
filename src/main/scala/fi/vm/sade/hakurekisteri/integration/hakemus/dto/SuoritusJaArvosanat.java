package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Map;

public class SuoritusJaArvosanat {

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

}
