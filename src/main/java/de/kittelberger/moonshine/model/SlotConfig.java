package de.kittelberger.moonshine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlotConfig {

  private String component;
  private int order;
  @Builder.Default
  private boolean enabled = true;

}
