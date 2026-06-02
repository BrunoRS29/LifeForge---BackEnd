# Dockerfile do microsservico ML do LifeForge
# Base slim para reduzir superficie de ataque e tamanho da imagem.
# Multi-stage poderia ser usado se quisessemos otimizar mais, mas como
# scikit-learn ja traz seus binarios, o ganho seria marginal.

FROM python:3.12-slim

# Variaveis de ambiente recomendadas para Python em container
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Instala dependencias primeiro para aproveitar cache de layer
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copia codigo apos as deps (mudanca de codigo nao invalida o cache do pip)
COPY app/ ./app/

# Usuario nao-root para hardening
RUN useradd --create-home --shell /bin/bash mlservice \
    && chown -R mlservice:mlservice /app
USER mlservice

EXPOSE 8000

# Healthcheck nativo do Docker para o compose esperar o servico estar pronto
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health').read()" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
