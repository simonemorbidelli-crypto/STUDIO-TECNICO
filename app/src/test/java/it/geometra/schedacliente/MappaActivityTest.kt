package it.geometra.schedacliente

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MappaActivityTest {
    @Test fun ammetteSoloGeoscopioHttps() {
        assertTrue(MappaActivity.urlAmmesso("https://www502.regione.toscana.it/ows_catasto/x?map=owscatasto"))
        assertTrue(MappaActivity.urlAmmesso("https://geodataserver.appenninosettentrionale.it/arcgis/rest/services/ProgPAI_2022/x/MapServer/identify?f=json"))
        assertFalse(MappaActivity.urlAmmesso("https://geodataserver.appenninosettentrionale.it/portal/sharing/rest"))
        assertFalse(MappaActivity.urlAmmesso("https://geodataserver.appenninosettentrionale.it.evil.com/arcgis/rest/services/x"))
        assertFalse(MappaActivity.urlAmmesso("http://www502.regione.toscana.it/x"))
        assertFalse(MappaActivity.urlAmmesso("https://altro.example.com/x"))
        assertFalse(MappaActivity.urlAmmesso("https://www502.regione.toscana.it@evil.com/x"))
        assertFalse(MappaActivity.urlAmmesso("file:///etc/passwd"))
    }

    @Test fun apreSoloCollegamentiDellaRegione() {
        assertTrue(MappaActivity.urlApribile("https://www502.regione.toscana.it/geoscopio/download/ctr10k/SHP/287/S_287060_2019_3003_.zip"))
        assertTrue(MappaActivity.urlApribile("https://www.regione.toscana.it/-/geoscopio"))
        assertFalse(MappaActivity.urlApribile("http://www502.regione.toscana.it/x.zip"))
        assertFalse(MappaActivity.urlApribile("https://evil.example.com/x.zip"))
        assertTrue(MappaActivity.urlApribile("https://www.gazzettaufficiale.it/atto/serie_generale/caricaDettaglioAtto/originario?atto.codiceRedazionale=20A01864"))
        assertTrue(MappaActivity.urlApribile("https://www.appenninosettentrionale.it/"))
        assertTrue(MappaActivity.urlApribile("https://geodataserver.appenninosettentrionale.it/portal/apps/webappviewer/index.html?id=14ca15f41e8e4396a1b27d788db98f60"))
        assertTrue(MappaActivity.urlApribile("https://pdgadj.appenninosettentrionale.it/DSBhome/simplexSWB/IT09CI_N002AR019FI/"))
        assertTrue(MappaActivity.urlApribile("http://www.adbarno.it/rep/decreti/057_2021.pdf"))
        assertFalse(MappaActivity.urlApribile("http://www.appenninosettentrionale.it/"))
        assertFalse(MappaActivity.urlApribile("http://evil.adbarno.it/x"))
        assertTrue(MappaActivity.urlApribile("https://it.wikipedia.org/wiki/Codice_catastale"))
        assertTrue(MappaActivity.urlApribile("https://www301.regione.toscana.it/bancadati/uffici/Strutture.xml"))
        assertFalse(MappaActivity.urlApribile("https://regione.toscana.it.evil.example.com/x"))
        assertFalse(MappaActivity.urlApribile("http://www.gazzettaufficiale.it/x"))
        assertFalse(MappaActivity.urlApribile("intent://x#Intent;end"))
    }
}
