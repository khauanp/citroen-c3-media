import MapKit
import SwiftUI

/** Interactive route overview for the iPhone companion. */
struct RoutePreviewMap: UIViewRepresentable {
    let routeCoordinates: [CLLocationCoordinate2D]
    let currentCoordinate: CLLocationCoordinate2D?
    let destination: DestinationResult?
    let routeRevision: Int
    let fitRequestID: Int

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView(frame: .zero)
        mapView.delegate = context.coordinator
        mapView.mapType = .mutedStandard
        mapView.showsCompass = true
        mapView.showsScale = true
        mapView.showsTraffic = true
        mapView.showsUserLocation = true
        mapView.isScrollEnabled = true
        mapView.isZoomEnabled = true
        mapView.isRotateEnabled = true
        mapView.isPitchEnabled = true
        mapView.pointOfInterestFilter = .includingAll
        return mapView
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        let routeChanged = context.coordinator.routeRevision != routeRevision
        if routeChanged {
            context.coordinator.routeRevision = routeRevision
            mapView.removeOverlays(mapView.overlays.filter { $0 is MKPolyline })
            mapView.removeAnnotations(mapView.annotations.filter { !($0 is MKUserLocation) })

            if routeCoordinates.count >= 2 {
                var coordinates = routeCoordinates
                let polyline = MKPolyline(coordinates: &coordinates, count: coordinates.count)
                mapView.addOverlay(polyline, level: .aboveRoads)
                context.coordinator.routePolyline = polyline
            } else {
                context.coordinator.routePolyline = nil
            }

            if let destination {
                let annotation = RouteDestinationAnnotation()
                annotation.coordinate = destination.coordinate
                annotation.title = destination.title
                annotation.subtitle = destination.subtitle
                mapView.addAnnotation(annotation)
            }
        }

        if routeChanged || context.coordinator.fitRequestID != fitRequestID {
            context.coordinator.fitRequestID = fitRequestID
            fitRoute(on: mapView, coordinator: context.coordinator)
        }
    }

    private func fitRoute(on mapView: MKMapView, coordinator: Coordinator) {
        if let polyline = coordinator.routePolyline, !polyline.boundingMapRect.isNull {
            mapView.setVisibleMapRect(
                polyline.boundingMapRect,
                edgePadding: UIEdgeInsets(top: 54, left: 34, bottom: 58, right: 34),
                animated: true
            )
            return
        }

        if let currentCoordinate, let destination {
            let points = [currentCoordinate, destination.coordinate].map { MKMapPoint($0) }
            let rect = points.reduce(MKMapRect.null) { partial, point in
                partial.union(MKMapRect(x: point.x, y: point.y, width: 1, height: 1))
            }
            mapView.setVisibleMapRect(
                rect,
                edgePadding: UIEdgeInsets(top: 54, left: 34, bottom: 58, right: 34),
                animated: true
            )
        } else if let destination {
            mapView.setRegion(
                MKCoordinateRegion(
                    center: destination.coordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.06, longitudeDelta: 0.06)
                ),
                animated: true
            )
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var routeRevision = -1
        var fitRequestID = -1
        var routePolyline: MKPolyline?

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let polyline = overlay as? MKPolyline else { return MKOverlayRenderer(overlay: overlay) }
            let renderer = MKPolylineRenderer(polyline: polyline)
            renderer.strokeColor = .systemBlue
            renderer.lineWidth = 7
            renderer.lineCap = .round
            renderer.lineJoin = .round
            return renderer
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard annotation is RouteDestinationAnnotation else { return nil }
            let identifier = "c3-destination"
            let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView)
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: identifier)
            view.annotation = annotation
            view.canShowCallout = true
            view.markerTintColor = .systemRed
            view.glyphImage = UIImage(systemName: "flag.checkered")
            view.titleVisibility = .adaptive
            return view
        }
    }
}

private final class RouteDestinationAnnotation: MKPointAnnotation {}
