package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 形象管理工作区；所有业务记录均来自当前成员的真实录入。 */
public record AppearanceWorkspaceDto(
        List<Garment> garments,
        List<Outfit> outfits,
        Profile profile,
        List<Plan> plans,
        List<WearLog> wearLogs,
        List<Note> notes,
        String principles,
        LocalDateTime updatedAt) {

    public record Garment(String id, String name, String category, List<String> seasons,
            List<String> styles, List<String> colors, List<String> occasions, String material,
            String status, String imageId, LocalDate purchaseDate, LocalDate lastWornDate,
            int wearCount, String note) { }

    public record Outfit(String id, String name, List<String> garmentIds, List<String> scenarios,
            String imageId, String note) { }

    public record Profile(Double heightCm, Double weightKg, Double shoulderCm, Double waistCm,
            LocalDate measuredOn, String skinTone, String faceShape, String hairTexture,
            String stylePreferences, String avoidances, String postureGoal, Integer hairScore,
            Integer clothingScore, Integer shoesScore, Integer accessoryScore, Integer bodyScore,
            Integer presenceScore) { }

    public record Plan(String id, String type, String title, LocalDate dueDate, String scenario,
            String outfitId, String status, String note) { }

    public record WearLog(String id, LocalDate date, String outfitId, List<String> garmentIds,
            String feeling) { }

    public record Note(String id, String title, String content, List<String> tags,
            LocalDateTime createdAt) { }
}
