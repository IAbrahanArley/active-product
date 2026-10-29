-- Registros anteriores não têm proprietário confiável e permanecem inacessíveis pela API.
-- Sua atribuição exige conferência operacional; nunca inferir o dono pelo primeiro GET.
ALTER TABLE activation_requests ADD COLUMN consumer_id VARCHAR(200);
