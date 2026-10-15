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
        assertFalse(MappaActivity.urlApribile("intent://x#Intent;end"))
    }
}
