package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

import org.joda.time.LocalDate;

import java.util.*;

public class Arvosana {
    public static final DescendingMyonnettyOrder NEWEST_FIRST = new DescendingMyonnettyOrder();

    private String id;
    private String suoritus;
    private String aine;
    private Boolean valinnainen = false;
    private Integer jarjestys;
    private String myonnetty;
    private String source;
    private Map<String, String> lahdeArvot = new HashMap<>();
    private Arvio arvio = new Arvio();
    private String lisatieto;

    public Arvosana() {
    }

    public Arvosana(String id, String suoritus, String aine, Boolean valinnainen, String myonnetty, String source, Map<String, String> lahdeArvot, Arvio arvio, String lisatieto) {
        this.id = id;
        this.suoritus = suoritus;
        this.aine = aine;
        this.valinnainen = valinnainen;
        this.myonnetty = myonnetty;
        this.source = source;
        this.lahdeArvot = lahdeArvot;
        this.arvio = arvio;
        this.lisatieto = lisatieto;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setAine(String aine) {
        this.aine = aine;
    }

    public void setArvio(Arvio arvio) {
        this.arvio = arvio;
    }

    public void setLisatieto(String lisatieto) {
        this.lisatieto = lisatieto;
    }

    public void setMyonnetty(String myonnetty) {
        this.myonnetty = myonnetty;
    }

    public void setSuoritus(String suoritus) {
        this.suoritus = suoritus;
    }

    public void setValinnainen(Boolean valinnainen) {
        this.valinnainen = valinnainen;
    }

    public void setJarjestys(Integer jarjestys) {
        this.jarjestys = jarjestys;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Boolean isValinnainen() {
        return valinnainen;
    }

    public Boolean getValinnainen() {
        return valinnainen;
    }

    public Integer getJarjestys() {
        return jarjestys;
    }

    public Arvio getArvio() {
        return arvio;
    }

    public String getAine() {
        return aine;
    }

    public String getId() {
        return id;
    }

    public String getMyonnetty() {
        return myonnetty;
    }

    public String getSource() {
        return source;
    }

    public String getSuoritus() {
        return suoritus;
    }

    public String getLisatieto() {
        return lisatieto;
    }


    public Map<String, String> getLahdeArvot() {
        return lahdeArvot;
    }

    public void setLahdeArvot(Map<String, String> lahdeArvot) {
        this.lahdeArvot = lahdeArvot;
    }

    @Override
    public String toString() {
        return "Arvosana{" +
                "id='" + id + '\'' +
                ", suoritus='" + suoritus + '\'' +
                ", aine='" + aine + '\'' +
                ", valinnainen=" + valinnainen +
                ", myonnetty='" + myonnetty + '\'' +
                ", source='" + source + '\'' +
                ", lahdeArvot=" + lahdeArvot +
                ", arvio=" + arvio +
                ", lisatieto='" + lisatieto + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Arvosana arvosana = (Arvosana) o;

        if (id != null ? !id.equals(arvosana.id) : arvosana.id != null) return false;
        if (suoritus != null ? !suoritus.equals(arvosana.suoritus) : arvosana.suoritus != null) return false;
        if (aine != null ? !aine.equals(arvosana.aine) : arvosana.aine != null) return false;
        if (valinnainen != null ? !valinnainen.equals(arvosana.valinnainen) : arvosana.valinnainen != null)
            return false;
        if (myonnetty != null ? !myonnetty.equals(arvosana.myonnetty) : arvosana.myonnetty != null) return false;
        if (source != null ? !source.equals(arvosana.source) : arvosana.source != null) return false;
        if (lahdeArvot != null ? !lahdeArvot.equals(arvosana.lahdeArvot) : arvosana.lahdeArvot != null) return false;
        return !(lisatieto != null ? !lisatieto.equals(arvosana.lisatieto) : arvosana.lisatieto != null);

    }

    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (suoritus != null ? suoritus.hashCode() : 0);
        result = 31 * result + (aine != null ? aine.hashCode() : 0);
        result = 31 * result + (valinnainen != null ? valinnainen.hashCode() : 0);
        result = 31 * result + (myonnetty != null ? myonnetty.hashCode() : 0);
        result = 31 * result + (source != null ? source.hashCode() : 0);
        result = 31 * result + (lahdeArvot != null ? lahdeArvot.hashCode() : 0);
        result = 31 * result + (arvio != null ? arvio.hashCode() : 0);
        result = 31 * result + (lisatieto != null ? lisatieto.hashCode() : 0);
        return result;
    }

    public boolean isMyonnettyAfter(Arvosana a) {
        return NEWEST_FIRST.compare(this, a) < 0;
    }

    public static class DescendingMyonnettyOrder implements Comparator<Arvosana> {
        @Override
        public int compare(Arvosana first, Arvosana second) {
            if (first.myonnetty == null && second.myonnetty == null) {
                return 0;
            }
            if (first.myonnetty == null) {
                return -1;
            }
            if (second.myonnetty == null) {
                return 1;
            }

            LocalDate firstMyonnetty = LocalDate.parse(first.myonnetty, SuoritusJaArvosanatWrapper.ARVOSANA_PVM_FORMATTER);
            LocalDate secondMyonnetty = LocalDate.parse(second.myonnetty, SuoritusJaArvosanatWrapper.ARVOSANA_PVM_FORMATTER);
            return secondMyonnetty.compareTo(firstMyonnetty);
        }

        @Override
        public boolean equals(Object obj) {
            return obj == this;
        }
    }
}
