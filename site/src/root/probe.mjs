import { $, connectAndroid, isWebUsb, problem, show } from './common.mjs';

const button = $('check-phone');
const status = $('phone-status');
if (isWebUsb()) {
  button.disabled = false;
  show(status, 'The check reads Android properties. It does not install, patch or flash anything.');
}
button.addEventListener('click', async () => {
  button.disabled = true;
  $('phone-detail').hidden = true;
  let connection;
  try {
    show(status, 'Select the Sonim phone in the USB chooser.');
    connection = await connectAndroid(status);
    const { adb, battery, fingerprint } = connection;
    const tool = await adb.shell('command -v su');
    $('firmware').textContent = fingerprint;
    $('battery').textContent = `${battery}%`;
    $('superuser').textContent = tool.code === 0 ? 'su is present; permission was not requested.' : 'No su found in the shell’s PATH.';
    $('phone-detail').hidden = false;
    show(status, battery >= 40 ? 'XP3800, Android 8.1, 32-bit ARM. Compatibility check passed.' : 'XP3800 identified. Charge it to at least 40% before service work.', battery < 40);
  } catch (error) {
    show(status, problem(error), true);
  } finally {
    await connection?.adb.close();
    button.disabled = false;
  }
});
