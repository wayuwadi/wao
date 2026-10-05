import * as THREE from 'three'
import type { LivingWeather } from './livingWeather'
export const wardrobeNecklines = [-0.25, -0.35, -0.57, -0.38] as const

const wardrobes = [
  { light: 0xe5eee8, jacket: 0x718db8, winter: 0x617997, raincoat: 0xeebc51, scarf: 0xd88e50 },
  { light: 0xd7b383, jacket: 0xab8056, winter: 0x8c715c, raincoat: 0x86b8c4, scarf: 0x6f9392 },
  { light: 0xf2d8de, jacket: 0xc48da6, winter: 0xae819a, raincoat: 0xbca0de, scarf: 0xedb25b },
  { light: 0xf0ecd0, jacket: 0x879c71, winter: 0x708661, raincoat: 0xf0b480, scarf: 0x739fbb },
] as const

export function disposeWardrobe(group: THREE.Object3D) {
  group.traverse((object) => {
    const mesh = object as THREE.Mesh
    mesh.geometry?.dispose()
    if (mesh.material)
      (Array.isArray(mesh.material) ? mesh.material : [mesh.material]).forEach((material) =>
        material.dispose(),
      )
  })
  group.removeFromParent()
}

/** 衣服沿已有身体曲面裁出，原毛绒、脸与配饰不被替换。 */
export function createCompanionWardrobe(body: THREE.Group, index: number, weather: LivingWeather) {
  const group = new THREE.Group()
  group.name = 'companion-wardrobe'
  body.add(group)
  if (weather.clothes === 'plain' && !weather.scarf) return group
  const palette = wardrobes[index]!
  const color = weather.clothes === 'plain' ? palette.jacket : palette[weather.clothes]
  const material = new THREE.MeshStandardMaterial({
    color,
    roughness: 0.94,
    side: THREE.DoubleSide,
  })
  const trim = new THREE.MeshStandardMaterial({ color: 0xf2e7d3, roughness: 0.9 })
  const neckline = wardrobeNecklines[index]!
  const surface = body.getObjectByName('companion-fur-body')?.children[0] as THREE.Mesh | undefined
  if (surface?.geometry && weather.clothes !== 'plain') {
    const base = surface.geometry.toNonIndexed(),
      position = base.getAttribute('position'),
      vertices: number[] = []
    for (let n = 0; n < position.count; n += 3) {
      // 保留跨边界三角形，顶点裁到领口平面，避免锯齿和漏出的毛尖。
      if ([0, 1, 2].every((k) => position.getY(n + k) > neckline)) continue
      for (let k = 0; k < 3; k++)
        vertices.push(
          position.getX(n + k) * 1.035,
          Math.min(neckline, position.getY(n + k)) * 1.02,
          position.getZ(n + k) * 1.055,
        )
    }
    base.dispose()
    const geometry = new THREE.BufferGeometry()
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3))
    geometry.computeVertexNormals()
    const coat = new THREE.Mesh(geometry, material)
    coat.name = `companion-clothes-${weather.clothes}`
    group.add(coat)
    const seam = new THREE.Mesh(new THREE.BoxGeometry(0.025, 0.22, 0.03), trim)
    seam.position.set(index === 0 ? 0.17 : 0, neckline - 0.13, 0.69)
    group.add(seam)
    if (index === 0 || index === 1) {
      const pocket = new THREE.Mesh(new THREE.BoxGeometry(0.18, 0.1, 0.035), trim)
      pocket.position.set(0.36, neckline - 0.17, 0.62)
      group.add(pocket)
    }
  }
  if (weather.scarf) {
    const scarfMaterial = new THREE.MeshStandardMaterial({ color: palette.scarf, roughness: 1 })
    const scarf = new THREE.Mesh(
      new THREE.TorusGeometry(0.65, 0.065, 8, 32, Math.PI),
      scarfMaterial,
    )
    scarf.position.set(0, neckline - 0.04, 0.1)
    scarf.rotation.x = Math.PI / 2
    scarf.name = 'companion-scarf'
    group.add(scarf)
    const tail = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.24, 0.06), scarfMaterial)
    tail.position.set(0.42, neckline - 0.14, 0.67)
    tail.name = 'companion-scarf-tail'
    group.add(tail)
  }
  if (weather.clothes === 'winter' && index === 1) {
    const cap = new THREE.Mesh(
      new THREE.SphereGeometry(0.5, 20, 12, 0, Math.PI * 2, 0, Math.PI / 2),
      material,
    )
    cap.position.set(0, 0.94, 0)
    cap.scale.set(0.9, 0.5, 1)
    group.add(cap)
    const pom = new THREE.Mesh(new THREE.SphereGeometry(0.095, 12, 8), trim)
    pom.position.set(0, 1.21, 0)
    group.add(pom)
  }
  // 没有引用到的材质也及时释放，场景统一负责组内其余资源。
  const used = new Set<THREE.Material>()
  group.traverse((object) => {
    const mesh = object as THREE.Mesh
    if (mesh.material)
      (Array.isArray(mesh.material) ? mesh.material : [mesh.material]).forEach((value) =>
        used.add(value),
      )
  })
  if (!used.has(material)) material.dispose()
  if (!used.has(trim)) trim.dispose()
  return group
}
