<p align="center">
  <img src="./zamolxis-icon.png" width="180" height="180" alt="Zamolxis" />
</p>

# Zamolxis

**[Русский](README.md) | [English](README.en.md) | Română**

[![CI](https://github.com/c0d3craft3r13/zamolxis/actions/workflows/ci.yml/badge.svg)](https://github.com/c0d3craft3r13/zamolxis/actions/workflows/ci.yml)

Zamolxis este un mesager și un serviciu de apeluri vocale pentru Android care nu depinde de internet, de antenele de telefonie mobilă sau de vreun server.
Mesajele și apelurile trec direct de la un dispozitiv la altul prin Bluetooth, Wi-Fi, radio (LoRa) sau prin oricare nod al rețelei Reticulum aflat la îndemână.

Fără conturi.
Fără numere de telefon.
Fără înregistrare.
Nu are ce să fie blocat și nu are ce să fie confiscat.

---

<p align="center">
  <img src="./docs/images/hero-banner.jpg" alt="Zamolxis — legătura care nu poate fi oprită" />
</p>

<p align="center">
  <a href="./docs/media/promo-clip.mp4">
    <img src="./docs/images/promo.webp" width="760" alt="Un mesaj trecând din telefon în telefon peste orașul nocturn" />
  </a>
</p>

<p align="center"><sub>Apăsați pentru a deschide clipul întreg</sub></p>

---

### Pentru cine este

- Jurnaliști și activiști care lucrează sub cenzură sau în timpul întreruperilor de comunicații
- Oameni aflați în expediții, în zone îndepărtate sau în situații de urgență
- Oricine preferă ca discuțiile sale private să nu treacă prin serverele altcuiva

---

### Prin ce se deosebește Zamolxis

| Funcție                                      | Mesagere obișnuite | Columba¹ | **Zamolxis**                 |
|----------------------------------------------|--------------------|----------|------------------------------|
| Funcționează fără internet                    | Nu                 | Da       | **Da**                       |
| Fără conturi și fără servere centrale         | Nu                 | Da       | **Da**                       |
| Criptare post-cuantică                        | Rareori            | Nu       | **Da (X25519 + ML-KEM-768)** |
| Conversații criptate pe dispozitiv            | Parțial            | Nu       | **Da (SQLCipher)**           |
| Blocarea aplicației cu PIN                    | Da                 | Nu       | **Da**                       |
| **PIN de constrângere** (distruge datele)     | Aproape nicăieri   | Nu       | **Da**                       |
| Capturi de ecran și înregistrare blocate      | Rareori            | Nu       | **Da**                       |
| Discuții de grup                              | Da                 | Nu       | **Da**                       |
| Apeluri vocale                                | Da                 | Da       | **Da**                       |
| Hărți offline și partajare sigură a locației  | Nu                 | Da       | **Da**                       |

<sub>¹ Columba este proiectul din al cărui cod a crescut Zamolxis. Comparația reflectă starea upstream la 21 august 2026.</sub>

---

### Ce contează cel mai mult

**1. Apărare împotriva constrângerii — PIN-ul de constrângere**
După ce ați stabilit PIN-ul obișnuit, puteți adăuga un al doilea: cel de urgență.
Arată exact la fel. Dacă sunteți silit să deblocați aplicația, introduceți PIN-ul de urgență. Toate datele sunt distruse iremediabil, iar aplicația arată ca și cum tocmai ar fi fost instalată. Fără avertisment, fără confirmare și fără vreo urmă că ar fi existat un al doilea PIN.

PIN-ul de urgență funcționează chiar și atunci când aplicația este blocată temporar după încercări greșite — altfel, cine v-a luat telefonul și a apăsat câteva cifre la nimereală v-ar fi luat și ieșirea de urgență.

**2. Apărare împotriva viitorului**
Între utilizatorii Zamolxis, mesajele sunt sigilate suplimentar cu criptare hibridă post-cuantică (X25519 + ML-KEM-768). Chiar dacă traficul este înregistrat astăzi, descifrarea lui mai târziu va fi extrem de grea.

Implicit, aceasta lucrează în regimul „acolo unde se poate": sigiliul se aplică dacă interlocutorul îl înțelege și dacă legătura poate duce octeții în plus — pe radio LoRa, lent, aceștia costă secunde de timp de emisie. Pentru cine vrea o garanție, în setări există regimul strict: nu trimite deloc dacă mesajul nu poate fi sigilat.

<p align="center">
  <img src="./docs/images/feature-post-quantum.jpg" width="620" alt="O scrisoare sigilată într-un ornament dacic" />
</p>

**3. Datele sub cheie**
Toate conversațiile de pe telefon se află într-o bază de date criptată cu SQLCipher, sub o cheie păstrată în magazia hardware de chei a Android. Copierea bazei de date de pe dispozitiv nu o face lizibilă.

**4. Ecranul sub cheie**
Capturile de ecran, înregistrarea ecranului și transmisia sunt blocate, iar Android nu păstrează nicio miniatură a aplicației pentru meniul „Recente" — fără asta, o aplicație blocată tot ar arăta ultima conversație oricui trage cu degetul în sus. Activat implicit, se poate dezactiva din setări.

**5. Independență adevărată**
Funcționează prin Bluetooth cu cei din apropiere, prin radio la distanță și prin orice noduri ale rețelei, când sunt disponibile. Internetul nu este obligatoriu.

<p align="center">
  <img src="./docs/images/feature-mesh.jpg" width="620" alt="Un mesaj sărind din telefon în telefon peste orașul nocturn" />
</p>

**6. Nicio urmă**
Fără conturi, fără server central, fără catalog de utilizatori. Nu are ce să fie confiscat și nu are ce să fie blocat.

---

### Ce știe să facă acum

- Mesaje și apeluri vocale fără internet
- Discuții de grup
- PIN de constrângere — distrugerea de urgență a datelor
- Întârziere crescătoare după încercări greșite de PIN
- Blocarea capturilor de ecran și a înregistrării
- Mai multe identități pe același dispozitiv
- Partajare sigură a locației și hărți offline
- Răsfoirea paginilor NomadNetwork
- Copie de rezervă a cheilor și mutarea identității pe alt telefon
- Aspect complet personalizabil

Istoricul conversațiilor **nu** părăsește dispozitivul, și asta este intenționat: baza de date este criptată cu o cheie care nu iese niciodată din telefon, așa că o copie făcută în altă parte oricum nu s-ar deschide. Se mută cheile și identitatea, nu mesajele.

---

### Cum funcționează, pe înțelesul tuturor

<p align="center">
  <img src="./docs/images/how-it-works.png" alt="O cheie creată pe telefon, un schimb de cod QR, transmiterea prin Bluetooth, Wi-Fi, radio și ștafetă, citită doar de destinatar" />
</p>

Închipuiți-vă o rețea în care telefoanele, stațiile radio și calculatoarele se găsesc singure între ele și trimit mesajele mai departe — ca un lanț viu.
Niciun server „principal" nu este necesar.

A căzut internetul — legătura rămâne.
Au fost oprite antenele — legătura rămâne.
Sunteți într-un loc fără nicio acoperire — se poate folosi un modul radio.

Zamolxis este construit pe protocolul deschis [Reticulum](https://reticulum.network/), unul dintre cele mai rezistente moduri de a comunica fără infrastructură centralizată.

<p align="center">
  <img src="./docs/images/feature-direct.jpg" width="620" alt="Două telefoane comunicând direct, antena barată" />
</p>

---

### Instalare

1. Descărcați APK-ul de pe pagina [Releases](https://github.com/c0d3craft3r13/zamolxis/releases)
2. Verificați semnătura — procedura este în [SECURITY.md](./SECURITY.md); nu săriți peste acest pas
3. Instalați aplicația și creați-vă identitatea chiar în ea

Sau instalați prin Obtainium:

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/c0d3craft3r13/zamolxis">
  <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="56" alt="Obțineți din Obtainium" />
</a>

---

### Securitate

Zamolxis este făcut pentru situațiile în care adversarul poate controla rețeaua sau poate pune fizic mâna pe dispozitiv.

- Mesajele sunt criptate cap la cap, iar între utilizatorii Zamolxis și post-cuantic
- Baza de date de pe dispozitiv este criptată
- Există un PIN obișnuit, o întârziere după încercări greșite și un **PIN de constrângere** care distruge datele
- Ecranul este protejat împotriva capturilor, a înregistrării și a transmisiei

Modelul complet al amenințărilor și ceea ce **nu** este încă acoperit se află în [SECURITY.md](./SECURITY.md).

Ați găsit o vulnerabilitate? Raportați-o **numai** prin [GitHub Security Advisories](https://github.com/c0d3craft3r13/zamolxis/security/advisories/new). Nu deschideți niciodată o problemă publică în care să o descrieți.

---

### De ce numele „Zamolxis"

Zamolxis era un zeu al dacilor, legat de nemurire.
Se spune că s-a retras într-o încăpere subpământeană vreme de trei ani și toți l-au crezut mort. Apoi s-a întors.

Un nume potrivit pentru o rețea care poate să tacă — și să reapară.

---

### Sprijiniți proiectul

Zamolxis este complet gratuit și cu sursă deschisă.

**USDT (TRC-20, rețeaua Tron):**
`TNPzvsfsdNC3XrxJPB2NMVh1nZSPcvZzxC`

<p align="center">
  <img src="./docs/images/donate-usdt-trc20.png" width="160" alt="Cod QR pentru donații USDT TRC-20" />
</p>

---

### Licență

Mozilla Public License 2.0 — vedeți [LICENSE.md](./LICENSE.md).

Proiectul se bazează pe codul [Columba](https://github.com/torlando-tech/columba), dar se dezvoltă independent.
Autorii proiectului original nu au legătură cu Zamolxis și nu îl susțin.
