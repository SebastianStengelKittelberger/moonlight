package de.kittelberger.moonshine.model;

import lombok.Data;


@Data
public class MapConfig {

  private DTOType dtoType;
  private String ukey;
  private String mappingType;
  private String targetField;
  private String targetFieldType;
  private String javaCode;
  private Boolean isFallback;
  private TargetType target;
}
