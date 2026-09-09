import * as THREE from 'three'
import atlasUrl from '@/assets/pets/zhang-sanfeng/spritesheet.webp'
import { PET_ATLAS, petFrameAt, petLookFrame, type PetSpriteState } from '@/utils/petSprite'
import type { WorldState } from './world'

/** 场景仅持有绘制资源；输入与角色位置由 Vue 和独立规则层管理。 */
export async function createWorldScene(
  host: HTMLElement,
  signal: AbortSignal,
  onContextLost: () => void,
) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  renderer.domElement.setAttribute('aria-hidden', 'true')
  host.prepend(renderer.domElement)
  const scene = new THREE.Scene()
  scene.background = new THREE.Color('#dce5dc')
  scene.fog = new THREE.Fog('#dce5dc', 28, 62)
  const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100)
  const geometries = new Set<THREE.BufferGeometry>()
  const materials = new Set<THREE.Material>()
  let texture: THREE.Texture | undefined
  let disposed = false
  const material = (color: string, roughness = 0.85) => {
    const value = new THREE.MeshStandardMaterial({ color, roughness })
    materials.add(value)
    return value
  }
  const stone = material('#c2c2ad'),
    paleStone = material('#ded7ba'),
    wood = material('#685c44')
  const wall = material('#eee5cd'),
    roof = material('#335e59'),
    trim = material('#b29764')
  const grass = material('#8b9b72'),
    foliage = material('#52745c'),
    darkFoliage = material('#3d6051')
  const mesh = (
    geometry: THREE.BufferGeometry,
    mat: THREE.Material,
    x: number,
    y: number,
    z: number,
  ) => {
    geometries.add(geometry)
    const obj = new THREE.Mesh(geometry, mat)
    obj.position.set(x, y, z)
    obj.castShadow = true
    obj.receiveShadow = true
    scene.add(obj)
    return obj
  }
  const box = (
    w: number,
    h: number,
    d: number,
    mat: THREE.Material,
    x: number,
    y: number,
    z: number,
  ) => mesh(new THREE.BoxGeometry(w, h, d), mat, x, y, z)
  const cylinder = (r: number, h: number, mat: THREE.Material, x: number, y: number, z: number) =>
    mesh(new THREE.CylinderGeometry(r, r, h, 48), mat, x, y, z)
  const hemisphere = new THREE.HemisphereLight('#fff7e8', '#566352', 2)
  scene.add(hemisphere)
  const sun = new THREE.DirectionalLight('#fff5dd', 2)
  sun.position.set(-12, 20, 8)
  sun.castShadow = true
  sun.shadow.mapSize.set(2048, 2048)
  Object.assign(sun.shadow.camera, { left: -18, right: 18, top: 18, bottom: -18, near: 1, far: 60 })
  sun.shadow.bias = -0.001
  scene.add(sun)
  box(26, 0.65, 26, stone, 0, -0.45, 0)
  box(24, 0.12, 24, grass, 0, -0.08, 0)
  box(6, 0.08, 22, paleStone, 0, 0.01, 0)
  box(20, 0.08, 3, paleStone, 0, 0.02, -2.8)
  // 石板缝隙与台阶保留细节，避免平铺外部游戏纹理。
  for (let z = -10; z <= 10; z += 1.1)
    for (let x = -2; x <= 2; x += 2) {
      box(1.93, 0.04, 1.04, Math.round(z * 10) % 3 === 0 ? stone : paleStone, x, 0.07, z)
    }
  for (const x of [-12, 12]) box(0.35, 1, 24, wall, x, 0.4, 0)
  box(24, 1, 0.35, wall, 0, 0.4, -12)
  box(8, 0.22, 4.3, stone, 0, 0.11, -8)
  box(7.2, 3.8, 3.4, wall, 0, 2, -8)
  box(1.8, 2.9, 0.12, wood, 0, 1.55, -6.24)
  for (const x of [-2.5, 2.5]) {
    box(1.2, 1.4, 0.13, wood, x, 2.3, -6.23)
    for (let i = -0.4; i <= 0.4; i += 0.2) box(0.04, 1.3, 0.06, trim, x + i, 2.3, -6.13)
  }
  const roofShape = (x: number, z: number, w: number, d: number, y: number) => {
    for (const side of [-1, 1]) {
      const slope = box(w, 0.18, d / 2 + 0.5, roof, x, y, z + (side * d) / 4)
      slope.rotation.x = side * 0.35
      box(w + 0.2, 0.13, 0.15, trim, x, y - d * 0.09, z + side * (d / 2 + 0.2))
    }
    box(w + 0.2, 0.17, 0.22, roof, x, y + d * 0.09, z)
  }
  roofShape(0, -8, 8.3, 4.7, 4.3)
  box(2.2, 0.55, 0.12, roof, 0, 3.48, -6.2)
  // 听雨亭：四柱和双坡顶。
  box(3.8, 0.24, 3.8, stone, -7.5, 0.12, -2.8)
  for (const x of [-8.9, -6.1]) for (const z of [-4.2, -1.4]) box(0.17, 2.9, 0.17, wood, x, 1.6, z)
  roofShape(-7.5, -2.8, 4.3, 4.3, 3.2)
  box(2.7, 0.2, 0.65, wood, -7.5, 0.7, -3.8)
  // 荷池与莲叶。
  box(4.4, 0.26, 6.4, stone, 7.2, 0.12, 2)
  const water = material('#609a93', 0.22)
  box(4, 0.03, 6, water, 7.2, 0.26, 2)
  for (let i = 0; i < 8; i++) {
    const x = 6 + Math.sin(i * 4.1) * 0.65 + (i % 2),
      z = -0.2 + i * 0.6
    cylinder(0.27, 0.02, foliage, x, 0.3, z)
    if (i % 3 === 0) mesh(new THREE.SphereGeometry(0.12, 8, 6), material('#dca9a1'), x, 0.39, z)
  }
  // 太极台由平面圆弧和阴阳鱼组成，无外部位图。
  cylinder(2.7, 0.16, stone, 0, 0.1, 1)
  const ink = material('#43564d'),
    ivory = material('#e8dfc4')
  const disc = (
    r: number,
    mat: THREE.Material,
    x: number,
    z: number,
    start = 0,
    length = Math.PI * 2,
    y = 0.19,
  ) => {
    const obj = mesh(new THREE.CircleGeometry(r, 64, start, length), mat, x, y, z)
    obj.rotation.x = -Math.PI / 2
  }
  disc(2.15, ivory, 0, 1)
  disc(2.15, ink, 0, 1, 0, Math.PI, 0.195)
  disc(1.075, ink, 1.075, 1, 0, Math.PI * 2, 0.2)
  disc(1.075, ivory, -1.075, 1, 0, Math.PI * 2, 0.205)
  disc(0.25, ivory, 1.075, 1, 0, Math.PI * 2, 0.21)
  disc(0.25, ink, -1.075, 1, 0, Math.PI * 2, 0.215)
  // 松树与远山使用确定性位置，刷新保持同一个庭院。
  for (const [x, z, size] of [
    [-9, 7, 1.2],
    [-8, -9, 1],
    [9, -8, 1.3],
    [10, 9, 0.9],
    [-10, 2, 0.7],
    [5, -10, 0.8],
  ]) {
    const s = size!,
      px = x!,
      pz = z!
    box(0.25 * s, 2.8 * s, 0.25 * s, wood, px, 1.4 * s, pz)
    for (let j = 0; j < 3; j++)
      mesh(
        new THREE.ConeGeometry((1.5 - j * 0.25) * s, 1.5 * s, 7),
        j % 2 ? darkFoliage : foliage,
        px,
        (2.1 + j * 0.75) * s,
        pz,
      )
  }
  const mountain = material('#9baea0')
  for (let i = 0; i < 11; i++)
    mesh(
      new THREE.ConeGeometry(5 + (i % 3), 7 + (i % 4) * 2, 5),
      mountain,
      -35 + i * 7,
      1,
      -30 - (i % 2) * 4,
    )
  const shadowMaterial = new THREE.MeshBasicMaterial({
    color: '#263b31',
    transparent: true,
    opacity: 0.2,
    depthWrite: false,
  })
  materials.add(shadowMaterial)
  const shadow = mesh(new THREE.CircleGeometry(0.45, 24), shadowMaterial, 0, 0.24, 5)
  shadow.rotation.x = -Math.PI / 2
  shadow.castShadow = false
  const contextLost = (event: Event) => {
    event.preventDefault()
    onContextLost()
  }
  renderer.domElement.addEventListener('webglcontextlost', contextLost)
  const dispose = () => {
    if (disposed) return
    disposed = true
    renderer.domElement.removeEventListener('webglcontextlost', contextLost)
    geometries.forEach((g) => g.dispose())
    materials.forEach((m) => m.dispose())
    texture?.dispose()
    renderer.dispose()
    renderer.forceContextLoss()
    renderer.domElement.remove()
    signal.removeEventListener('abort', dispose)
  }
  signal.addEventListener('abort', dispose, { once: true })
  try {
    texture = await new THREE.TextureLoader().loadAsync(atlasUrl)
    if (signal.aborted) {
      texture.dispose()
      dispose()
      throw new Error('场景已关闭')
    }
    texture.colorSpace = THREE.SRGBColorSpace
    texture.magFilter = THREE.NearestFilter
    texture.minFilter = THREE.LinearFilter
    texture.repeat.set(1 / PET_ATLAS.columns, 1 / PET_ATLAS.rows)
    const petMaterial = new THREE.SpriteMaterial({
      map: texture,
      transparent: true,
      alphaTest: 0.05,
    })
    materials.add(petMaterial)
    const pet = new THREE.Sprite(petMaterial)
    pet.center.set(0.5, 0)
    pet.scale.set((1.6 * 192) / 208, 1.6, 1)
    scene.add(pet)
    let previousAnimation = '',
      startedAt = 0
    return {
      dispose,
      render(state: WorldState, yaw: number, distance: number, waving: boolean, time: number) {
        if (disposed) return
        const width = Math.max(1, host.clientWidth),
          height = Math.max(1, host.clientHeight)
        if (
          renderer.domElement.width !== Math.floor(width * renderer.getPixelRatio()) ||
          renderer.domElement.height !== Math.floor(height * renderer.getPixelRatio())
        ) {
          renderer.setSize(width, height, false)
          camera.aspect = width / height
          camera.updateProjectionMatrix()
        }
        camera.position.set(
          state.x + Math.sin(yaw) * distance,
          distance * 0.8,
          state.z + Math.cos(yaw) * distance,
        )
        camera.lookAt(state.x, 0.3, state.z)
        const facing = state.heading + yaw
        const animation: PetSpriteState | 'look' =
          state.y > 0
            ? 'jumping'
            : state.moving
              ? Math.sin(facing) < 0
                ? 'running-left'
                : 'running-right'
              : waving
                ? 'waving'
                : 'look'
        if (animation !== previousAnimation) {
          startedAt = time
          previousAnimation = animation
        }
        const frame =
          animation === 'look'
            ? petLookFrame((facing * 180) / Math.PI)
            : petFrameAt(animation, time - startedAt)
        texture!.offset.set(
          frame.column / PET_ATLAS.columns,
          (PET_ATLAS.rows - 1 - frame.row) / PET_ATLAS.rows,
        )
        pet.position.set(state.x, 0.24 + state.y, state.z)
        shadow.position.set(state.x, 0.235, state.z)
        shadow.scale.setScalar(1 - Math.min(0.4, state.y * 0.2))
        renderer.render(scene, camera)
      },
    }
  } catch (error) {
    dispose()
    throw error
  }
}
