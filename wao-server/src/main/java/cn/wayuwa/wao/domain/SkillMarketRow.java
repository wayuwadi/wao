package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

/** Skill 市场列表与详情的连表投影。 */
public class SkillMarketRow {
    private String id; private String slug; private String displayName; private String summary; private String category;
    private String tagsJson; private String status; private Long downloadCount; private Long favoriteCount;
    private LocalDateTime updatedAt; private String publisherId; private String publisherHandle; private String publisherName;
    private String versionId; private String version; private String licenseCode; private String testedPlatformsJson;
    private String compatiblePlatformsJson; private String sha256; private String fileManifestJson; private String skillMd;
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getSlug(){return slug;} public void setSlug(String v){slug=v;}
    public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;}
    public String getSummary(){return summary;} public void setSummary(String v){summary=v;}
    public String getCategory(){return category;} public void setCategory(String v){category=v;}
    public String getTagsJson(){return tagsJson;} public void setTagsJson(String v){tagsJson=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Long getDownloadCount(){return downloadCount;} public void setDownloadCount(Long v){downloadCount=v;}
    public Long getFavoriteCount(){return favoriteCount;} public void setFavoriteCount(Long v){favoriteCount=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
    public String getPublisherId(){return publisherId;} public void setPublisherId(String v){publisherId=v;}
    public String getPublisherHandle(){return publisherHandle;} public void setPublisherHandle(String v){publisherHandle=v;}
    public String getPublisherName(){return publisherName;} public void setPublisherName(String v){publisherName=v;}
    public String getVersionId(){return versionId;} public void setVersionId(String v){versionId=v;}
    public String getVersion(){return version;} public void setVersion(String v){version=v;}
    public String getLicenseCode(){return licenseCode;} public void setLicenseCode(String v){licenseCode=v;}
    public String getTestedPlatformsJson(){return testedPlatformsJson;} public void setTestedPlatformsJson(String v){testedPlatformsJson=v;}
    public String getCompatiblePlatformsJson(){return compatiblePlatformsJson;} public void setCompatiblePlatformsJson(String v){compatiblePlatformsJson=v;}
    public String getSha256(){return sha256;} public void setSha256(String v){sha256=v;}
    public String getFileManifestJson(){return fileManifestJson;} public void setFileManifestJson(String v){fileManifestJson=v;}
    public String getSkillMd(){return skillMd;} public void setSkillMd(String v){skillMd=v;}
}
