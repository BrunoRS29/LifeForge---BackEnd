"""
Configuracoes do microsservico ML.

Carrega valores de variaveis de ambiente (ou .env em desenvolvimento)
usando pydantic-settings. Centralizar aqui evita ler `os.environ` espalhado
pelo codigo e facilita o teste (basta sobrescrever a instancia).
"""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Configuracao do servico, lida de variaveis de ambiente."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="ML_",
        case_sensitive=False,
    )

    # ------------------------------------------------------------------
    # Servidor
    # ------------------------------------------------------------------
    app_name: str = "lifeforge-ml-service"
    app_version: str = "0.1.0"
    log_level: str = Field(default="INFO", pattern="^(DEBUG|INFO|WARNING|ERROR)$")

    # ------------------------------------------------------------------
    # Modelos de regressao de renda
    # ------------------------------------------------------------------
    # Numero minimo de observacoes para treinar a regressao de renda.
    # Abaixo desse limiar a API responde 422 com mensagem clara, em vez
    # de devolver um modelo absurdo treinado em 1-2 pontos.
    income_min_observations: int = 6

    # Maximo de meses a projetar no /predict/income.
    # Limite duro: alem disso a incerteza acumulada torna a predicao inutil
    # e o teste de Anscombe (4 datasets) ilustra bem o problema.
    income_max_horizon_months: int = 60

    # ------------------------------------------------------------------
    # Random Forest de despesas
    # ------------------------------------------------------------------
    expense_min_observations: int = 12  # pelo menos 1 ano de historico
    expense_rf_n_estimators: int = 100
    expense_rf_max_depth: int = 10
    expense_rf_random_state: int = 42  # reprodutibilidade nos testes


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Retorna a instancia unica de Settings.

    Usamos lru_cache para evitar reparsing das variaveis a cada chamada.
    Testes podem usar `get_settings.cache_clear()` para reler o ambiente.
    """
    return Settings()
