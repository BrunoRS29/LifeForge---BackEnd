# Comparação: projeção determinística × Monte Carlo

> Artefato gerado por `EngineAnalysisTest`. A projeção determinística usa a fórmula de juros compostos com aportes (Seção 6.1): P(t) = P₀·(1+r)ᵗ + A·[((1+r)ᵗ − 1)/r]. O Monte Carlo (20.000 cenários, mesmo baseline da análise de sensibilidade) adiciona a camada estocástica.

| Cenário | Determinístico | MC — média | MC — mediana | MC — P10 | MC — P90 | P(sucesso) |
|---|---|---|---|---|---|---|
| 0% vol | R$ 1,371,046 | R$ 1,371,046 | R$ 1,371,046 | R$ 1,371,046 | R$ 1,371,046 | 100.0% |
| 10% vol | R$ 1,371,046 | R$ 1,369,797 | R$ 1,294,493 | R$ 867,969 | R$ 1,968,128 | 79.4% |
| 15% vol | R$ 1,371,046 | R$ 1,368,997 | R$ 1,206,510 | R$ 673,806 | R$ 2,258,196 | 65.7% |
| 25% vol | R$ 1,371,046 | R$ 1,366,555 | R$ 972,170 | R$ 395,126 | R$ 2,724,358 | 48.6% |

## Leitura

- **Sem volatilidade**, o Monte Carlo converge para o determinístico — validação de que o motor implementa corretamente a fórmula 6.1.
- **Com volatilidade**, a *média* do MC permanece próxima do determinístico, mas a *mediana* cai (assimetria / volatility drag) e abre-se a faixa P10–P90: a incerteza que a projeção determinística simplesmente ignora.
- O valor agregado do Monte Carlo é **quantificar risco** (P10, P90, probabilidade de sucesso), e não entregar um único número pontual — núcleo da hipótese do TCC.

## Conclusão para a validação estatística

O método tradicional (planilha determinística) entrega **um único número** (R$ 1,371 mi) e sugere 100% de confiança. O Monte Carlo mostra que, com volatilidade realista de 15% a.a., esse mesmo plano tem apenas **65,7%** de chance de atingir a meta, com cenário pessimista (P10) de R$ 674 mil. É exatamente a diferença de previsibilidade/qualidade de decisão que a hipótese do TCC afirma.
