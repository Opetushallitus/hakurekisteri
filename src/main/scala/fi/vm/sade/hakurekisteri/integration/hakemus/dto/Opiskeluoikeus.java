package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

// id: "d6bbbe98-9d92-4fcf-8827-631591ec6b47",
// aika: {
// alku: "2000-01-01T00:00:00.000+02:00",
// loppu: "2014-01-01T00:00:00.000+02:00"
// },
// henkiloOid: "1.2.246.562.24.92484332807",
// komo: "koulutus_671116",
// myontaja: "1.2.246.562.10.16546622305",
// source: "1.2.246.562.24.00000000001"
// }
public class Opiskeluoikeus {
    private String id;
    private String henkiloOid;
    private String komo;
    private String myontaja;
    private String source;
    private Aika aika;

    public Aika getAika() {
        return aika;
    }

    public String getHenkiloOid() {
        return henkiloOid;
    }

    public String getId() {
        return id;
    }

    public String getKomo() {
        return komo;
    }

    public String getMyontaja() {
        return myontaja;
    }

    public String getSource() {
        return source;
    }

}
