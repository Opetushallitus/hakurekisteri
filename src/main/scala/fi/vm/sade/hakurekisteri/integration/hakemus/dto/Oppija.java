package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

import com.google.common.collect.Lists;

import java.util.List;

public class Oppija {
    private String oppijanumero;
    private List<Opiskelu> opiskelu;
    private List<SuoritusJaArvosanat> suoritukset = Lists.newArrayList();
    private List<Opiskeluoikeus> opiskeluoikeudet;
    private Boolean ensikertalainen;

    public void setEnsikertalainen(Boolean ensikertalainen) {
        this.ensikertalainen = ensikertalainen;
    }

    public Boolean isEnsikertalainen() {
        return ensikertalainen;
    }

    public List<Opiskelu> getOpiskelu() {
        return opiskelu;
    }

    public List<SuoritusJaArvosanat> getSuoritukset() {
        return suoritukset;
    }

    public void setSuoritukset(List<SuoritusJaArvosanat> suoritukset) {
        this.suoritukset = suoritukset;
    }

    public List<Opiskeluoikeus> getOpiskeluoikeudet() {
        return opiskeluoikeudet;
    }

    public String getOppijanumero() {
        return oppijanumero;
    }

    public void setOppijanumero(String oppijanumero) {
        this.oppijanumero = oppijanumero;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Oppija oppija = (Oppija) o;

        if (ensikertalainen != oppija.ensikertalainen) return false;
        if (oppijanumero != null ? !oppijanumero.equals(oppija.oppijanumero) : oppija.oppijanumero != null)
            return false;
        if (opiskelu != null ? !opiskelu.equals(oppija.opiskelu) : oppija.opiskelu != null) return false;
        return !(opiskeluoikeudet != null ? !opiskeluoikeudet.equals(oppija.opiskeluoikeudet) : oppija.opiskeluoikeudet != null);

    }

    @Override
    public int hashCode() {
        int result = oppijanumero != null ? oppijanumero.hashCode() : 0;
        result = 31 * result + (opiskelu != null ? opiskelu.hashCode() : 0);
        result = 31 * result + (suoritukset != null ? suoritukset.hashCode() : 0);
        result = 31 * result + (opiskeluoikeudet != null ? opiskeluoikeudet.hashCode() : 0);
        result = 31 * result + (ensikertalainen ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Oppija{" +
                "oppijanumero='" + oppijanumero + '\'' +
                ", opiskelu=" + opiskelu +
                ", suoritukset=" + suoritukset +
                ", opiskeluoikeudet=" + opiskeluoikeudet +
                ", ensikertalainen=" + ensikertalainen +
                '}';
    }
}
