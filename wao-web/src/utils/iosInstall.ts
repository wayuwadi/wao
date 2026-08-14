type IosNavigator = Pick<Navigator, 'userAgent' | 'platform' | 'maxTouchPoints'> & {
  standalone?: boolean
}

type DisplayModeWindow = Pick<Window, 'matchMedia'>

export const IOS_INSTALL_DISMISSED_KEY = 'wao-ios-install-guide-v1'

/** iPadOS 桌面 UA 会伪装成 Mac，触控点是识别它的必要条件。 */
export function isIosDevice(nav: IosNavigator = navigator): boolean {
  return /iPad|iPhone|iPod/.test(nav.userAgent)
    || (nav.platform === 'MacIntel' && nav.maxTouchPoints > 1)
}

/** iOS 既保留私有 standalone 字段，也支持标准 display-mode 查询，两条都要覆盖。 */
export function isStandaloneApp(
  nav: IosNavigator = navigator,
  displayWindow: DisplayModeWindow = window,
): boolean {
  return nav.standalone === true || displayWindow.matchMedia('(display-mode: standalone)').matches
}

export function shouldOfferIosInstall(
  nav: IosNavigator = navigator,
  displayWindow: DisplayModeWindow = window,
): boolean {
  return isIosDevice(nav) && !isStandaloneApp(nav, displayWindow)
}
