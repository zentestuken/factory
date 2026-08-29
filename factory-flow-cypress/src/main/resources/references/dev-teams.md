# FOLIO development teams

The canonical mapping between a story's Jira **Development Team** (`customfield_10057`) and
TestRail's **Dev Team** dropdown (`custom_dev_team`). Both cypress workers read this file:
the generator uses it to normalise the team name it reads off the ticket, and the TestRail
sync uses it to turn that name into the numeric option id the API expects.

`DevTeamCatalog` parses the table below. To change the data, edit this file — no code change
is needed. Rules the parser relies on:

- One row per team. Empty cells are allowed and mean "not known yet".
- Lookup is case-insensitive and tolerates a trailing `Team`, so `promin`, `Promin` and
  `Promin Team` all resolve to `Promin`.
- The two name columns differ in places (Jira `Bama` vs TestRail `BAMA`); both are indexed,
  so a value from either system resolves.
- A row with an empty **TestRail ID** is still a *recognised* team. Sync logs a warning naming
  the team and leaves TestRail's Dev Team field unset, rather than silently dropping it — that
  is the signal to come back and fill the id in here. Every team below currently has an id;
  the empty case exists for teams added to Jira before anyone looks them up in TestRail.

To find a missing TestRail ID, read the options of the `custom_dev_team` field from the
TestRail API (`get_case_fields`) and match on the TestRail name column.

| Jira name | Jira ID | TestRail name | TestRail ID |
|---|---|---|---|
| Athena | 11379 | Athena | 26 |
| Bama | 10140 | BAMA | 14 |
| Bienenvolk | 10141 | Bienenvolk | 2 |
| Big FC | 10717 | Big FC | 23 |
| Citation | 10142 | Citation | 18 |
| Corsair | 10145 | Corsair | 19 |
| Dresden | 11080 | Dresden | 24 |
| Eureka | 10149 | Eureka | 21 |
| Fenrir | 11478 | Fenrir | 29 |
| Firebird | 10152 | Firebird | 3 |
| Folijet | 10153 | Folijet | 4 |
| Gutenberg | 10158 | Gutenberg | 16 |
| Helios | 11313 | Helios | 28 |
| K-Int | 10159 | K-Int | 25 |
| Klemming | 10542 | Klemming | 22 |
| Leipzig | 10163 | Leipzig | 10 |
| MOL | 10165 | MOL | 15 |
| Odin | 10169 | Odin | 17 |
| Promin | 11346 | Promin | 27 |
| Reporting | 10174 | Reporting | 12 |
| Scout | 10177 | Scout | 11 |
| Sif | 10178 | Sif | 20 |
| Spitfire | 10180 | Spitfire | 6 |
| Thor | 10184 | Thor | 7 |
| Thunderjet | 10185 | Thunderjet | 8 |
| Vega | 10187 | Vega | 9 |
| Volaris | 10188 | Volaris | 13 |
