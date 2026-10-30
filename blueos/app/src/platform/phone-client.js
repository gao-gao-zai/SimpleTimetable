// Optional reverse role only. GT2 firmware in the history has no BLE client API.
export function clientCapability(bluetooth) {
  const b=bluetooth&&bluetooth.BLE
  return !!(b&&typeof b.createGattClientDevice==='function'&&typeof b.startBLEScan==='function'&&typeof b.subscribeBLEDeviceFind==='function')
}
export function explainReverseRole() {
  const bluetooth=require('@blueos.bluetooth.ble')
  if(!clientCapability(bluetooth))throw Error('当前固件缺少 BLE 客户端能力，请使用手表提供服务、手机扫描连接')
  return '当前接口提供客户端入口；备用反向连接尚未完成本次真机验证，请使用普通课表同步'
}
