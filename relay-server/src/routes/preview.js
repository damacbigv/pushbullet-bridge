import { Router } from 'express';
import { getLinkPreview } from '../controllers/previewController.js';

const router = Router();

router.post('/', getLinkPreview);
router.get('/', getLinkPreview);

export default router;
