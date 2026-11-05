import { Router } from 'express';
import {
  postNotification,
  postSms,
  postClipboard,
  getPendingEvents
} from '../controllers/syncController.js';

const router = Router();

router.post('/notification', postNotification);
router.post('/sms', postSms);
router.post('/clipboard', postClipboard);
router.get('/pending', getPendingEvents);

export default router;
