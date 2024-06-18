#!/bin/bash
for i in {1..99}
do
   curl -s -S -X POST 'https://testi.virkailija.opintopolku.fi/service-access/accessTicket?client_id=robotti&client_secret=Testaaja!&service_url=https://testi.virkailija.opintopolku.fi/suoritusrekisteri' -o ticket
   curl -F "data=@henkilot_${i}.xml" -H "CasSecurityTicket: `cat ticket`" https://testi.virkailija.opintopolku.fi/suoritusrekisteri/rest/v1/siirto/perustiedot -o resp.json
   mv resp.json > "henkilot_${1}.json"
   rm ticket
done


