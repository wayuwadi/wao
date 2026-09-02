package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 不可变 Skill 版本及审核快照。 */
@TableName("skill_version")
public class SkillVersionEntity {
    @TableId private String id;
    private String skillId; private String version; private String displayName; private String summary; private String category;
    private String tagsJson; private String changelog; private String licenseCode; private Boolean rightsConfirmed;
    private String testedPlatformsJson; private String compatiblePlatformsJson; private String state;
    private String packageName; private Long packageSize; private Long unpackedSize; private Integer fileCount;
    private String sha256; private String fileManifestJson; private String scanFindingsJson; private Boolean scanOverrideConfirmed; private String skillMd;
    private byte[] packageContent; private LocalDateTime submittedAt; private LocalDateTime reviewedAt;
    private String reviewerId; private String reviewNote; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getSkillId(){return skillId;} public void setSkillId(String v){skillId=v;}
    public String getVersion(){return version;} public void setVersion(String v){version=v;}
    public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;}
    public String getSummary(){return summary;} public void setSummary(String v){summary=v;}
    public String getCategory(){return category;} public void setCategory(String v){category=v;}
    public String getTagsJson(){return tagsJson;} public void setTagsJson(String v){tagsJson=v;}
    public String getChangelog(){return changelog;} public void setChangelog(String v){changelog=v;}
    public String getLicenseCode(){return licenseCode;} public void setLicenseCode(String v){licenseCode=v;}
    public Boolean getRightsConfirmed(){return rightsConfirmed;} public void setRightsConfirmed(Boolean v){rightsConfirmed=v;}
    public String getTestedPlatformsJson(){return testedPlatformsJson;} public void setTestedPlatformsJson(String v){testedPlatformsJson=v;}
    public String getCompatiblePlatformsJson(){return compatiblePlatformsJson;} public void setCompatiblePlatformsJson(String v){compatiblePlatformsJson=v;}
    public String getState(){return state;} public void setState(String v){state=v;}
    public String getPackageName(){return packageName;} public void setPackageName(String v){packageName=v;}
    public Long getPackageSize(){return packageSize;} public void setPackageSize(Long v){packageSize=v;}
    public Long getUnpackedSize(){return unpackedSize;} public void setUnpackedSize(Long v){unpackedSize=v;}
    public Integer getFileCount(){return fileCount;} public void setFileCount(Integer v){fileCount=v;}
    public String getSha256(){return sha256;} public void setSha256(String v){sha256=v;}
    public String getFileManifestJson(){return fileManifestJson;} public void setFileManifestJson(String v){fileManifestJson=v;}
    public String getScanFindingsJson(){return scanFindingsJson;} public void setScanFindingsJson(String v){scanFindingsJson=v;}
    public Boolean getScanOverrideConfirmed(){return scanOverrideConfirmed;} public void setScanOverrideConfirmed(Boolean v){scanOverrideConfirmed=v;}
    public String getSkillMd(){return skillMd;} public void setSkillMd(String v){skillMd=v;}
    public byte[] getPackageContent(){return packageContent;} public void setPackageContent(byte[] v){packageContent=v;}
    public LocalDateTime getSubmittedAt(){return submittedAt;} public void setSubmittedAt(LocalDateTime v){submittedAt=v;}
    public LocalDateTime getReviewedAt(){return reviewedAt;} public void setReviewedAt(LocalDateTime v){reviewedAt=v;}
    public String getReviewerId(){return reviewerId;} public void setReviewerId(String v){reviewerId=v;}
    public String getReviewNote(){return reviewNote;} public void setReviewNote(String v){reviewNote=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
