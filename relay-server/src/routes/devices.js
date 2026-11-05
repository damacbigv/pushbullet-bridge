import { Router } from 'express';
import {
  registerDevice,
  listDevices,
  heartbeat
} from '../controllers/deviceController.js';

const router = Router();

router.post('/register', registerDevice);
router.get('/', listDevices);
router.post('/:deviceId/heartbeat', heartbeat);

export default router;
