#!/usr/bin/python3

# script to generate sql statements to update the hetu information in
# oppijanumerorekisteri's database.

import json

with open("student-results.json") as ytl_test_data_handle:
    ytl_test_data = json.loads(ytl_test_data_handle.read())

with open("person-oids-for-syksy-2018-haku.csv") as person_oids_from_haku_handle:
    person_oids_from_haku = person_oids_from_haku_handle.readlines()

i = 0

for row in ytl_test_data:
    print("update henkilo set hetu='{0}', query_hetu='{0}' where oidhenkilo='{1}';".format( \
          row["ssn"], person_oids_from_haku[i].strip()))
    i = i + 1
