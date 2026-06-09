# Comparação: projeção determinística × Monte Carlo

> Artefato gerado por `EngineAnalysisTest`. A projeção determinística usa a fórmula de juros compostos com aportes (Seção 6.1): P(t) = P₀·(1+r)ᵗ + A·[((1+r)ᵗ − 1)/r]. O Monte Carlo (20.000 cenários, mesmo baseline da análise de sensibilidade) adiciona a camada estocástica.

| Cenário | Determinístico | MC — média | MC — mediana | MC — P10 | MC — P90 | P(sucesso) |
|---|---|---|---|---|---|---|
| 0% vol | R$ 1,371,046 | R$ 1,371,046 | R$ 1,371,046 | R$ 1,371,046 | R$ 1,371,046 | 100.0% |
| 10% vol | R$ 1,371,046 | R$ 1,370,238 | R$ 1,292,087 | R$ 866,654 | R$ 1,973,978 | 79.5% |
| 15% vol | R$ 1,371,046 | R$ 1,370,544 | R$ 1,203,651 | R$ 669,982 | R$ 2,270,223 | 65.3% |
| 25% vol | R$ 1,371,046 | R$ 1,372,744 | R$ 968,425 | R$ 391,122 | R$ 2,751,826 | 48.3% |

## Leitura

- **Sem volatilidade**, o Monte Carlo converge para o determinístico — validação de que o motor implementa corretamente a fórmula 6.1.
- **Com volatilidade**, a *média* do MC permanece próxima do determinístico, mas a *mediana* cai (assimetria / volatility drag) e abre-se a faixa P10–P90: a incerteza que a projeção determinística simplesmente ignora.
- O valor agregado do Monte Carlo é **quantificar risco** (P10, P90, probabilidade de sucesso), e não entregar um único número pontual — núcleo da hipótese do TCC.
