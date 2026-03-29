package de.kittelberger.moonshine.controller;

import de.kittelberger.moonshine.service.RenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock RenderService renderService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(renderService)).build();
    }

    @Test
    void productHandler_returns200WithRenderedHtml() throws Exception {
        when(renderService.renderTemplate("de", "de", "SKU001", "produktseite", false))
                .thenReturn("<html><body>Product Page</body></html>");

        mockMvc.perform(get("/de/de/product-SKU001"))
                .andExpect(status().isOk())
                .andExpect(content().string("<html><body>Product Page</body></html>"));
    }

    @Test
    void productHandler_defaultsToProduktseite_whenNoPageParam() throws Exception {
        when(renderService.renderTemplate("de", "de", "SKU001", "produktseite", false))
                .thenReturn("<html>Default</html>");

        mockMvc.perform(get("/de/de/product-SKU001"))
                .andExpect(status().isOk());

        verify(renderService).renderTemplate("de", "de", "SKU001", "produktseite", false);
    }

    @Test
    void productHandler_withExplicitPageParam_passesPageToService() throws Exception {
        when(renderService.renderTemplate("de", "de", "SKU001", "produktseite", false))
                .thenReturn("<html>Produktseite</html>");

        mockMvc.perform(get("/de/de/product-SKU001").param("page", "produktseite"))
                .andExpect(status().isOk());

        verify(renderService).renderTemplate("de", "de", "SKU001", "produktseite", false);
    }

    @Test
    void productHandler_withCustomPageParam_passesCustomPageToService() throws Exception {
        when(renderService.renderTemplate("de", "de", "SKU001", "technische-daten", false))
                .thenReturn("<html>Tech Details</html>");

        mockMvc.perform(get("/de/de/product-SKU001").param("page", "technische-daten"))
                .andExpect(status().isOk())
                .andExpect(content().string("<html>Tech Details</html>"));

        verify(renderService).renderTemplate("de", "de", "SKU001", "technische-daten", false);
    }

    @Test
    void productHandler_differentCountryAndLanguage() throws Exception {
        when(renderService.renderTemplate("at", "de", "SKU999", "produktseite", false))
                .thenReturn("<html>AT Product</html>");

        mockMvc.perform(get("/at/de/product-SKU999"))
                .andExpect(status().isOk())
                .andExpect(content().string("<html>AT Product</html>"));

        verify(renderService).renderTemplate("at", "de", "SKU999", "produktseite", false);
    }

    @Test
    void productHandler_withEditMode_passesEditModeToService() throws Exception {
        when(renderService.renderTemplate("de", "de", "SKU001", "produktseite", true))
                .thenReturn("<html><body>Edit Mode</body></html>");

        mockMvc.perform(get("/de/de/product-SKU001").param("editMode", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("<html><body>Edit Mode</body></html>"));

        verify(renderService).renderTemplate("de", "de", "SKU001", "produktseite", true);
    }
}
