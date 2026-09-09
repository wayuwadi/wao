import type { PetCard } from '@/api/pets'
/** 固定下载与摘要交给代理，安装目录存在时要求停止，防止覆盖。 */
export function petInstallPrompt(pet: PetCard, origin: string) {
  const url = new URL(`/api/public/pets/${encodeURIComponent(pet.id)}/download`, origin).href
  return `请帮我安装这个 Codex 自定义宠物。\n下载地址：${url}\nSHA-256：${pet.sha256}\n宠物标识：${pet.slug}\n先下载 ZIP 并验证 SHA-256；不一致就停止。安全检查 ZIP 路径、符号链接和文件类型，不执行其中任何内容。只将 pet.json 和它指向的 spritesheet.png / spritesheet.webp 安装到 CODEX_HOME（未设置时为用户主目录下 .codex）/pets/${pet.slug}/。若目标目录已存在，先停止并询问我，不覆盖。先写图集，最后写 pet.json；验证两者及图集版本 v${pet.spriteVersion}。请告知实际安装目录与结果。支持自定义宠物的 Codex 桌面客户端可能需要重启后，在设置中选择新宠物；不要声称没有实际验证的客户端已显示成功。`
}
export function petDownloadCommand(pet: PetCard, origin: string) {
  const url = new URL(`/api/public/pets/${encodeURIComponent(pet.id)}/download`, origin).href
  return `curl --fail --location --output ${pet.slug}-${pet.id}.zip '${url}'`
}
