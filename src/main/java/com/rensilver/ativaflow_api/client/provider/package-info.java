/**
 * Porta de saída ActivationProviderClient, independente de transporte.
 * HttpActivationProviderClient usa RestClient para traduzir o protocolo do provedor
 * em resultados tipados, isolando-o dos demais componentes, sem persistência ou retry.
 */
package com.rensilver.ativaflow_api.client.provider;
