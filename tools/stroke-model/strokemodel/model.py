"""Stroke predictor: GRU over the recent samples, fused with sensors and context, regressing the
pen's offset at each future display frame. Small enough for per-frame CPU inference on a phone."""
from __future__ import annotations

import torch
from torch import nn

from .data import CONTEXT_FEATURES, HORIZONS, PER_SAMPLE, SENSOR_FEATURES


class StrokePredictor(nn.Module):
    def __init__(self, hidden: int = 64):
        super().__init__()
        self.gru = nn.GRU(PER_SAMPLE, hidden, batch_first=True)
        self.side = nn.Sequential(nn.Linear(SENSOR_FEATURES + CONTEXT_FEATURES, hidden), nn.GELU())
        self.head = nn.Sequential(nn.Linear(2 * hidden, hidden), nn.GELU(), nn.Linear(hidden, HORIZONS * 2))

    def forward(self, history: torch.Tensor, sensors: torch.Tensor, context: torch.Tensor) -> torch.Tensor:
        _, h = self.gru(history)
        z = torch.cat([h[-1], self.side(torch.cat([sensors, context], dim=1))], dim=1)
        return self.head(z).view(-1, HORIZONS, 2)
