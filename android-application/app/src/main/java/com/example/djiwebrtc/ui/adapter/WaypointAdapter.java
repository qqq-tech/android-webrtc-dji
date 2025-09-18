package com.example.djiwebrtc.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.djiwebrtc.R;
import com.example.djiwebrtc.ui.model.Waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WaypointAdapter extends RecyclerView.Adapter<WaypointAdapter.WaypointViewHolder> {
    private final List<Waypoint> waypoints = new ArrayList<>();

    public void submitList(List<Waypoint> items) {
        waypoints.clear();
        if (items != null) {
            waypoints.addAll(items);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public WaypointViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_waypoint, parent, false);
        return new WaypointViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull WaypointViewHolder holder, int position) {
        Waypoint waypoint = waypoints.get(position);
        holder.bind(waypoint, position);
    }

    @Override
    public int getItemCount() {
        return waypoints.size();
    }

    static class WaypointViewHolder extends RecyclerView.ViewHolder {
        private final TextView nameText;
        private final TextView coordsText;
        private final TextView altitudeText;

        WaypointViewHolder(@NonNull View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.waypointName);
            coordsText = itemView.findViewById(R.id.waypointCoords);
            altitudeText = itemView.findViewById(R.id.waypointAltitude);
        }

        void bind(Waypoint waypoint, int index) {
            String name = waypoint.getName() != null ? waypoint.getName() : String.format(Locale.getDefault(), "WP-%d", index + 1);
            if (waypoint.isVisited()) {
                nameText.setText(String.format(Locale.getDefault(), "%s • 완료", name));
            } else {
                nameText.setText(name);
            }
            coordsText.setText(String.format(Locale.getDefault(), "위도 %.5f, 경도 %.5f", waypoint.getLatitude(), waypoint.getLongitude()));
            altitudeText.setText(String.format(Locale.getDefault(), "고도 %.0fm", waypoint.getAltitude()));
        }
    }
}
