import sharp from "sharp";
import { writeFileSync, mkdirSync } from "node:fs";
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024"><rect width="1024" height="1024" fill="#50785f"/><path d="M260 372 L348 659 Q355 680 369 659 L496 414 L616 659 Q630 680 637 659 L721 372" fill="none" stroke="#f5f6ed" stroke-width="67" stroke-linecap="round" stroke-linejoin="round"/><circle cx="770" cy="284" r="45" fill="#e8ca85"/></svg>`;
writeFileSync("public/icon.svg", svg);
await sharp(Buffer.from(svg))
  .png()
  .toFile("ios/App/App/Assets.xcassets/AppIcon.appiconset/AppIcon-512@2x.png");
for (const [folder, size] of Object.entries({
  mdpi: 48,
  hdpi: 72,
  xhdpi: 96,
  xxhdpi: 144,
  xxxhdpi: 192,
})) {
  const dir = `android/app/src/main/res/mipmap-${folder}`;
  mkdirSync(dir, { recursive: true });
  for (const name of ["ic_launcher", "ic_launcher_round"])
    await sharp(Buffer.from(svg))
      .resize(size, size)
      .png()
      .toFile(`${dir}/${name}.png`);
  // Adaptive icon foreground remains within safe 66/108 bounds.
  const fg = svg
    .replace('<rect width="1024" height="1024" fill="#50785f"/>', "")
    .replace("<path", '<g transform="translate(140 140) scale(.727)"><path')
    .replace("</svg>", "</g></svg>");
  await sharp(Buffer.from(fg))
    .resize(Math.round((size * 108) / 48))
    .png()
    .toFile(`${dir}/ic_launcher_foreground.png`);
}

// Generate all native launch screens from the same code-owned brand artwork.
import { readdirSync } from "node:fs";
for (const dir of readdirSync("android/app/src/main/res").filter((d) =>
  d.startsWith("drawable"),
)) {
  const file = `android/app/src/main/res/${dir}/splash.png`;
  try {
    const { width, height } = await sharp(file).metadata();
    const size = Math.round(Math.min(width, height) * 0.22);
    const launch = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="#f6f7f3"/><svg x="${(width - size) / 2}" y="${(height - size) / 2}" width="${size}" height="${size}" viewBox="0 0 1024 1024">${svg.replace(/<svg[^>]+>/, "").replace("</svg>", "")}</svg></svg>`;
    await sharp(Buffer.from(launch))
      .png()
      .toFile(file + ".new");
    const { renameSync } = await import("node:fs");
    renameSync(file + ".new", file);
  } catch (error) {
    if (error.message?.includes("Input file is missing")) continue;
    throw error;
  }
}
for (const name of [
  "splash-2732x2732.png",
  "splash-2732x2732-1.png",
  "splash-2732x2732-2.png",
]) {
  const size = 360,
    canvas = 2732;
  const launch = `<svg xmlns="http://www.w3.org/2000/svg" width="${canvas}" height="${canvas}"><rect width="100%" height="100%" fill="#f6f7f3"/><svg x="${(canvas - size) / 2}" y="${(canvas - size) / 2}" width="${size}" height="${size}" viewBox="0 0 1024 1024">${svg.replace(/<svg[^>]+>/, "").replace("</svg>", "")}</svg></svg>`;
  await sharp(Buffer.from(launch))
    .png()
    .toFile(`ios/App/App/Assets.xcassets/Splash.imageset/${name}`);
}
