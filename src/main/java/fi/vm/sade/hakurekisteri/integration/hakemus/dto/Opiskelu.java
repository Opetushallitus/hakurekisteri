package fi.vm.sade.hakurekisteri.integration.hakemus.dto;

// id: "9fdddf4c-2e6c-415b-bf3e-ce70f9d41a01",
// oppilaitosOid: "1.2.246.562.10.16546622305",
// luokkataso: "10",
// luokka: "10A",
// henkiloOid: "1.2.246.562.24.92484332807",
// alkuPaiva: "2011-07-31T21:00:00.000Z",
// source: "1.2.246.562.24.00000000001"
public class Opiskelu {
    private String id;
    private String oppilaitosOid;
    private String luokkataso;
    private String luokka;
    private String henkiloOid;
    private String alkuPaiva;
    private String source;

    public String getAlkuPaiva() {
        return alkuPaiva;
    }

    public String getHenkiloOid() {
        return henkiloOid;
    }

    public String getId() {
        return id;
    }

    public String getLuokka() {
        return luokka;
    }

    public String getLuokkataso() {
        return luokkataso;
    }

    public String getOppilaitosOid() {
        return oppilaitosOid;
    }

    public String getSource() {
        return source;
    }

}
