# Base de Estatísticas de Referência (Calibração)

Esta base centraliza as **premissas de longo prazo** usadas para calibrar as
projeções e a Simulação de Monte Carlo (Proposta, Seção 6.2). O objetivo é
**democratizar** o planejamento: o usuário **não precisa** estimar retorno,
volatilidade, inflação ou risco de desemprego — esses valores vêm daqui,
derivados de dados públicos brasileiros e da literatura, e podem ser refinados
pelo microsserviço de IA com o histórico do próprio usuário.

- Código: `engine/statistics/ReferenceData.kt`
- Endpoint público: `GET /api/v1/reference-data`
- Valores **anuais** em fração (0,045 = 4,5%), salvo indicação.

## Mapeamento para as distribuições da Simulação de Monte Carlo (§6.2)

| Variável (proposta) | Distribuição | Parâmetro na base |
|---|---|---|
| Retorno de investimento | Normal/LogNormal | `byRiskProfile[perfil].{expectedReturnAnnual, volatilityAnnual}` |
| Variação de renda | Normal truncada | `salaryGrowth.{mean, stdDev}` (tendência); no motor, `incomeVolatilityAnnual` perturba o aporte mês a mês (Normal truncada em 0) |
| Evento de desemprego | Bernoulli | `byEmploymentType[vínculo].unemploymentProbAnnual` + `unemploymentDurationMonths` |
| Despesa inesperada | Poisson + Exponencial | `unexpectedExpenseAnnualFrequency` (λ) + `unexpectedExpenseMeanFractionOfIncome` |
| Inflação | Normal | `inflation.{mean, stdDev}` |

`ReferenceData.presetFor(perfilDeRisco, vínculo)` monta um conjunto pronto de
parâmetros (retorno, volatilidade, inflação, crescimento salarial, prob. de
desemprego e duração) para alimentar a engine.

O **choque de despesa inesperada** é aplicado mês a mês pela `MonteCarloEngine`:
o número de eventos no mês segue `Poisson(λ/12)` e a magnitude de cada evento
segue `Exponencial(média)`, com `média = unexpectedExpenseMeanFractionOfIncome ×
renda mensal prevista`. É ligado automaticamente na rota calibrada
(`/simulation/run-calibrated`); na rota clássica permanece desativado (λ = 0).

## Valores atuais

### Economia
| Item | Valor | Observação |
|---|---|---|
| Inflação (IPCA) | média 4,5% · desvio 2,5% | meta + tolerância histórica |
| SELIC | 10,5% | taxa básica de referência |
| Livre de risco (CDI/Tesouro Selic) | 10,0% | base da renda fixa |
| Volatilidade do CDI | 0,5% | renda fixa pós-fixada (ANBIMA IMA-S); usada no "cenário mais seguro" |

### Retorno × volatilidade por perfil de risco (carteira-tipo, nominal)
| Perfil | Retorno a.a. | Volatilidade a.a. |
|---|---|---|
| Conservador | 9% | 3% |
| Moderado | 11% | 10% |
| Arrojado | 13% | 18% |

### Carreira / renda
| Item | Valor |
|---|---|
| Crescimento salarial nominal | média 6% · desvio 3% |
| Duração típica de desemprego | 6 meses |

### Risco de desemprego por vínculo (probabilidade anual)
| Vínculo | Prob. anual | Volatilidade de renda |
|---|---|---|
| Servidor público | 1% | 2% |
| CLT | 8% | 5% |
| PJ | 12% | 12% |
| Autônomo | 15% | 18% |
| Empresário | 15% | 20% |
| Desconhecido (default) | 10% | — |

### Choques e demografia
| Item | Valor |
|---|---|
| Frequência de despesas inesperadas (λ Poisson) | 1,5 por ano |
| Magnitude média do choque | 0,5 × renda mensal |
| Expectativa de vida | 77 anos |

### Ativos reais, independência financeira e filhos
| Item | Valor |
|---|---|
| Depreciação anual de veículos | 10% |
| Valorização imobiliária anual | 5% |
| Taxa de retirada segura (regra dos 4%) | 4% |
| Custo mensal por filho — 0–3 anos | R$ 800 |
| Custo mensal por filho — 4–6 anos | R$ 950 |
| Custo mensal por filho — 7–12 anos | R$ 1.100 |
| Custo mensal por filho — 13–18 anos | R$ 1.600 |
| Custo mensal por filho — 19–24 anos | R$ 1.200 |

Esses parâmetros alimentam, respectivamente: depreciação de veículos e
valorização de imóveis na projeção; o índice de independência financeira
(FI/RE = patrimônio ÷ 25× gastos anuais); e o custo de filhos por faixa
etária somado às despesas ao longo do horizonte.

## Fontes
- **IPCA / SELIC / CDI:** Banco Central do Brasil (séries históricas do Sistema
  Gerenciador de Séries Temporais — SGS) e IBGE (IPCA).
- **Renda variável (retorno e volatilidade):** Ibovespa / B3, comportamento de
  longo prazo; ANBIMA (IMA / índices de renda fixa) para a renda fixa.
- **Desemprego e duração:** IBGE — PNAD Contínua (taxa de desocupação e tempo
  de procura por trabalho).
- **Expectativa de vida:** IBGE — Tábuas Completas de Mortalidade (Brasil).
- **Depreciação de veículos:** Tabela FIPE — séries históricas de preços médios
  por modelo/ano (perda de valor anual).
- **Valorização imobiliária:** Índice FipeZap de preços de imóveis; IBGE —
  IPCA grupo Habitação.
- **Regra dos 4% (taxa de retirada segura):** BENGEN, W. P. *Determining
  Withdrawal Rates Using Historical Data* (1994); COOPER; HUBBARD; WALZ —
  *Trinity Study* (1998).
- **Custo de filhos por faixa etária:** IBGE — Pesquisa de Orçamentos
  Familiares (POF), despesas de consumo por faixa etária e classe de renda.
- **Distribuições de Monte Carlo:** GLASSERMAN, P. *Monte Carlo Methods in
  Financial Engineering* (2003); HULL, J. C. *Options, Futures, and Other
  Derivatives*.

> São **premissas-base de longo prazo**, não previsões. Estão centralizadas e
> versionadas para serem auditáveis — quando há histórico do usuário, a camada
> de IA (regressão de renda, Random Forest de gastos, ARIMA de patrimônio)
> ajusta esses valores para o caso individual.
