package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

import java.util.Arrays;
import java.util.List;

public class Arvio {
    private static final List<String> IMPROBATUR = Arrays.asList("I", "I+", "I-", "I=");
    private String arvosana;
    private String asteikko;
    private Integer pisteet;

    public Arvio() {

    }

    public void setArvosana(String arvosana) {
        this.arvosana = arvosana;
    }

    public void setAsteikko(String asteikko) {
        this.asteikko = asteikko;
    }

    public void setPisteet(Integer pisteet) {
        this.pisteet = pisteet;
    }

    public Arvio(String arvosana, String asteikko, Integer pisteet) {
        this.arvosana = arvosana;
        this.asteikko = asteikko;
        this.pisteet = pisteet;
    }

    public String getAsteikko() {
        return asteikko;
    }



    public String getArvosana() {
        if(IMPROBATUR.contains(arvosana)) {
            return "I";
        } else {
            return arvosana;
        }
    }

    public Integer getPisteet() {
        return pisteet;
    }

    @Override
    public String toString() {
        return "Arvio{" +
                "arvosana='" + arvosana + '\'' +
                ", asteikko='" + asteikko + '\'' +
                ", pisteet=" + pisteet +
                '}';
    }
}
