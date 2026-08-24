/**
 * The project's own frontend customisation. Atlas discovers the functions registered here and folds
 * them into its expression catalog, so `{{demofns.orderLabel(...)}}` completes and validates like a
 * built-in — and `formatIban`, which nothing calls, is reported as an unused custom function.
 */
import { orderLabel, formatIban } from './helpers';

export default {
  additionalData: {
    demofns: { orderLabel, formatIban },
  },
};
