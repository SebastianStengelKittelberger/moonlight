package de.kittelberger.moonshine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.kittelberger.moonshine.model.TemplateProperties;
import de.kittelberger.moonshine.service.TemplateStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TemplateControllerTest {

    @Mock TemplateStorageService templateStorageService;

    MockMvc mockMvc;
    final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TemplateController(templateStorageService)).build();
    }

    @Test
    void listVorlagen_returns200WithList() throws Exception {
        when(templateStorageService.listVorlagen()).thenReturn(List.of("header", "footer"));

        mockMvc.perform(get("/vorlagen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("header"))
                .andExpect(jsonPath("$[1]").value("footer"));
    }

    @Test
    void getVorlage_returns200WithHtmlContent() throws Exception {
        when(templateStorageService.loadVorlage("header")).thenReturn("<div>Header</div>");

        mockMvc.perform(get("/vorlage/header").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string("<div>Header</div>"));
    }

    @Test
    void putVorlage_returns200AndDelegatesToService() throws Exception {
        mockMvc.perform(put("/vorlage/header")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("<div>New Header</div>"))
                .andExpect(status().isOk());

        verify(templateStorageService).saveVorlage("header", "<div>New Header</div>");
    }

    @Test
    void deleteVorlage_returns200AndDelegatesToService() throws Exception {
        mockMvc.perform(delete("/vorlage/header"))
                .andExpect(status().isOk());

        verify(templateStorageService).deleteVorlage("header");
    }

    @Test
    void getVorlageHistory_returns200WithHistoryList() throws Exception {
        List<Map<String, String>> history = List.of(
                Map.of("id", "v1", "timestamp", "2024-01-01")
        );
        when(templateStorageService.loadVorlageHistory("header")).thenReturn(history);

        mockMvc.perform(get("/vorlage/header/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("v1"))
                .andExpect(jsonPath("$[0].timestamp").value("2024-01-01"));
    }

    @Test
    void listPages_returns200WithPageNames() throws Exception {
        when(templateStorageService.listPageNames("de", "de")).thenReturn(List.of("produktseite"));

        mockMvc.perform(get("/de/de/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("produktseite"));
    }

    @Test
    void getPage_returns200WithTemplateProperties() throws Exception {
        TemplateProperties props = TemplateProperties.builder()
                .name("produktseite")
                .build();
        when(templateStorageService.loadPage("de", "de", "produktseite")).thenReturn(props);

        mockMvc.perform(get("/de/de/page/produktseite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("produktseite"));
    }

    @Test
    void putPage_returns200AndDelegatesToService() throws Exception {
        TemplateProperties props = TemplateProperties.builder()
                .name("produktseite")
                .build();

        mockMvc.perform(put("/de/de/page/produktseite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(props)))
                .andExpect(status().isOk());

        verify(templateStorageService).savePage(eq("de"), eq("de"), eq("produktseite"),
                any(TemplateProperties.class));
    }
}
