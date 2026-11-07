import { Router } from 'express';
import {
  registerDevice,
  listDevices,
  heartbeat,
  dispatchPeerCommand,
  getPendingPeerCommands
} from '../controllers/deviceController.js';

const router = Router();

router.post('/register', registerDevice);
router.get('/', listDevices);
router.post('/:deviceId/heartbeat', heartbeat);
router.post('/command', dispatchPeerCommand);
router.get('/:deviceId/commands', getPendingPeerCommands);

export default router;
